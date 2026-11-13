package com.orangecode.tianmu.consumer;

import com.alibaba.fastjson.JSON;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.service.BulletService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 消费者
 */
@Component
@Slf4j
@RocketMQMessageListener(topic = "tianmu-topic", consumerGroup = "tianmuorange-consumer-group")
public class RocketMQConsumer implements RocketMQListener<String>, org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener {

    @Resource
    private BulletService bulletService;

    @Resource private com.orangecode.tianmu.service.BulletIngestionService ingestion;

    @Override
    public void prepareStart(org.apache.rocketmq.client.consumer.DefaultMQPushConsumer consumer) {
        consumer.setConsumeMessageBatchMaxSize(com.orangecode.tianmu.service.BulletIngestionService.MAX_BATCH_SIZE);
        consumer.setPullBatchSize(com.orangecode.tianmu.service.BulletIngestionService.MAX_BATCH_SIZE);
        // Bound writers so row-lock waiters cannot occupy the entire shared JDBC pool.
        consumer.setConsumeThreadMin(4);
        consumer.setConsumeThreadMax(4);
        consumer.setPullInterval(200);
        consumer.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently) (messages, context) -> {
            try {
                var events = messages.stream().map(m -> JSON.parseObject(new String(m.getBody(), java.nio.charset.StandardCharsets.UTF_8), SendBulletRequest.class)).toList();
                ingestion.persistBatch(events);
                log.debug("Danmaku batch committed size={}", events.size());
                return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception failure) {
                // The batch transaction has rolled back. Isolate poison messages in new transactions;
                // acknowledge originals only after every failed item is handed back to the broker.
                log.warn("Danmaku batch failed; isolating size={}", messages.size());
                for (var message : messages) {
                    try {
                        ingestion.persist(JSON.parseObject(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8), SendBulletRequest.class));
                    } catch (Exception itemFailure) {
                        try {
                            consumer.sendMessageBack(message, context.getDelayLevelWhenNextConsume(), context.getMessageQueue().getBrokerName());
                        } catch (Exception retryFailure) {
                            if (retryFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                            log.warn("Danmaku retry handoff failed; retaining original batch", retryFailure);
                            return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                }
                return org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
    }

    @Override
    public void onMessage(String message) {
        SendBulletRequest event = JSON.parseObject(message, SendBulletRequest.class);
        // Persistence is idempotent. Throwing lets RocketMQ retry failures instead of acknowledging data loss.
        bulletService.saveBulletToMySQL(event);
    }
}

