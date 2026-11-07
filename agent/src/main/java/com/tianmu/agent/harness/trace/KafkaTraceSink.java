package com.tianmu.agent.harness.trace;

import org.apache.kafka.clients.producer.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Explicit acknowledgement is awaited by the export worker, never by the model/tool worker. */
public final class KafkaTraceSink implements TraceSink {
    private final Producer<String, String> producer;
    private final String topic;
    private final int closeSeconds;
    private final long acknowledgementMillis;
    public KafkaTraceSink(Producer<String, String> producer, String topic) {
        this(producer,topic,2);
    }
    public KafkaTraceSink(Producer<String,String> producer,String topic,int closeSeconds) {
        this(producer,topic,closeSeconds,8000);
    }
    public KafkaTraceSink(Producer<String,String> producer,String topic,int closeSeconds,long acknowledgementMillis) {
        if (topic == null || !topic.matches("[A-Za-z0-9._-]{1,249}") || topic.equals(".") || topic.equals(".."))
            throw new IllegalArgumentException("Invalid trace topic");
        this.producer = producer;
        this.topic = topic;
        this.closeSeconds=closeSeconds;
        if(acknowledgementMillis<1)throw new IllegalArgumentException("Invalid Kafka acknowledgement timeout");
        this.acknowledgementMillis=acknowledgementMillis;
    }
    @Override public void send(TraceEnvelope envelope, String json) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, envelope.traceId(), json);
        record.headers().add("schema_version", envelope.schemaVersion().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        record.headers().add("event_id", envelope.eventId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        producer.send(record).get(acknowledgementMillis, TimeUnit.MILLISECONDS);
    }
    @Override public void close() { producer.close(Duration.ofSeconds(closeSeconds)); }
}
