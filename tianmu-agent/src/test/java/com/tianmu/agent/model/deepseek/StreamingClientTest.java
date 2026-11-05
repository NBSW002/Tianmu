package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamingClientTest {
    HttpServer server;ObjectMapper json=new ObjectMapper();DeepSeekClient client;
    CountDownLatch first=new CountDownLatch(1),release=new CountDownLatch(1);boolean complete=true;
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            var request=json.readTree(exchange.getRequestBody());assertTrue(request.path("stream").asBoolean());assertFalse(request.has("thinking"));
            exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()){
                String event=": heartbeat\r\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}\r\n\r\n";
                for(byte value:event.getBytes(StandardCharsets.UTF_8))output.write(value);output.flush();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                if(complete){output.write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));output.flush();}
            }catch(java.io.IOException disconnected){}finally{exchange.close();}
        });server.start();
        client=new DeepSeekClient(new DeepSeekProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test","compatible-chat","compatible-chat",Duration.ofSeconds(1),384,1024),json);
    }
    @AfterEach void stop(){release.countDown();server.stop(0);}
    com.fasterxml.jackson.databind.JsonNode invoke(CallControl control){return client.completeHarness("compatible-chat",List.of(Map.of("role","user","content","hello")),List.of(),1024,control);}
    @Test void actualHttpDeliversTextBeforeCompletionAcrossByteBoundaries()throws Exception {
        var out=new CopyOnWriteArrayList<String>();var control=new CallControl(Instant.now().plusSeconds(5),new CancellationToken()).withTextOutput(s->{out.add(s);first.countDown();});
        var future=CompletableFuture.supplyAsync(()->invoke(control));assertTrue(first.await(3,TimeUnit.SECONDS));assertFalse(future.isDone());release.countDown();
        assertEquals("你好",future.get(3,TimeUnit.SECONDS).path("content").asText());assertEquals("你好",String.join("",out));
    }
    @Test void cancellationClosesBlockedBodyAndCannotReturnPartialAnswer()throws Exception {
        var token=new CancellationToken();var control=new CallControl(Instant.now().plusSeconds(5),token).withTextOutput(s->first.countDown());
        var future=CompletableFuture.supplyAsync(()->invoke(control));assertTrue(first.await(3,TimeUnit.SECONDS));token.cancel(StopReason.USER_CANCELLED);
        var failure=assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));assertEquals("CANCELLED",((AgentException)failure.getCause()).code());
    }
    @Test void disconnectedStreamIsFailedRatherThanFinalized()throws Exception {
        complete=false;release.countDown();assertThrows(AgentException.class,()->invoke(new CallControl(Instant.now().plusSeconds(3),new CancellationToken()).withTextOutput(s->{})));
    }
}
