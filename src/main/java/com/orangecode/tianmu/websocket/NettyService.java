package com.orangecode.tianmu.websocket;

import com.orangecode.tianmu.common.DanmakuMetrics;

import java.util.concurrent.TimeUnit;

import com.orangecode.tianmu.producer.RocketMQProducer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import com.orangecode.tianmu.application.SessionAuthentication;
import com.orangecode.tianmu.application.VideoLookup;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class NettyService {



    @Value("${netty.port}")
    private int port;

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);

    private final EventLoopGroup workerGroup = new NioEventLoopGroup(NettyRuntime.availableProcessors() * 2);
    // Redis, video lookups and MQ sends must not block socket IO threads.
    private final EventExecutorGroup messageGroup = new DefaultEventExecutorGroup(4,
            new DefaultThreadFactory("danmaku-business"), 1024, (task, executor) -> {
                DanmakuMetrics.rejected.increment();
                throw new java.util.concurrent.RejectedExecutionException("Danmaku business queue full or stopped");
            });

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
    public void sampleQueue() {
        long pending=0;
        for (var executor : messageGroup) if (executor instanceof SingleThreadEventExecutor single) pending+=single.pendingTasks();
        DanmakuMetrics.pendingTasks.set(pending);
    }

    private final RocketMQProducer rocketMQProducer;

    private final SessionAuthentication sessionAuthentication;
    private final VideoLookup videoLookup;
    private final RoomBatchBroadcaster roomBroadcasts;

    private Channel serverChannel;


    @PostConstruct
    public void start() {

        new Thread(() -> {
            try {
                run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Netty server start interrupted", e);
            }
        }, "netty-starter").start();
    }


    public void run() throws InterruptedException{
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .childOption(ChannelOption.SO_SNDBUF, 1024 * 1024)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        configurePipeline(pipeline, rocketMQProducer, sessionAuthentication, videoLookup, roomBroadcasts, messageGroup);
                    }
                });
        serverChannel = serverBootstrap.bind(port).addListener(future -> {
            if (future.isSuccess()) {
                log.info("Netty server started on port {}", port);
            } else {
                log.error("Failed to start netty server", future.cause());
            }
        }).sync().channel();
    }


    static void configurePipeline(ChannelPipeline pipeline, RocketMQProducer producer,
                                  SessionAuthentication authentication, VideoLookup videos, RoomBatchBroadcaster broadcasts,
                                  EventExecutorGroup executor) {
        pipeline.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(65536));
        pipeline.addLast(new WebSocketServerProtocolHandler("/ws/bulletScreen", null, true, 65536, false, true));
        pipeline.addLast(new ChannelTrafficShapingHandler(1024 * 1024, 1024 * 1024));
        pipeline.addLast(executor, new WebSocketHandler(producer, authentication, videos, broadcasts));
    }

    @PreDestroy
    public void destroy() {

        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }

        roomBroadcasts.close();
        // Pipeline removal may hop from business executors back to socket IO executors.
        messageGroup.shutdownGracefully().syncUninterruptibly();
        workerGroup.shutdownGracefully().syncUninterruptibly();
        bossGroup.shutdownGracefully().syncUninterruptibly();
    }

}
