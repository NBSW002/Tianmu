package com.tianmu.agent.harness.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Module-owned startup policy. Defaults preserve the pre-governance execution values. */
@ConfigurationProperties("harness.trace.delivery")
public record TraceDeliveryProperties(@DefaultValue("100") int initialDelayMillis,
        @DefaultValue("200") int pollMillis,
        @DefaultValue("100") int maxBatch,
        @DefaultValue("250") int retryBaseMillis,
        @DefaultValue("30000") int retryCapMillis,
        @DefaultValue("100") int retryJitterMillis,
        @DefaultValue("10000") int warningMillis,
        @DefaultValue("10") int closeWaitSeconds,
        @DefaultValue("2") int forcedCloseWaitSeconds,
        @DefaultValue("4") int queryParallelism,
        @DefaultValue("2000") int queryMaxSpans,
        @DefaultValue("10485760") int queryMaxBytes,
        @DefaultValue("5") int queryExecutionSeconds,
        @DefaultValue("2000") int queryConnectMillis,
        @DefaultValue("7000") int queryReadMillis,
        @DefaultValue("5") int kafkaMaxInFlight,
        @DefaultValue("1000") int kafkaMaxBlockMillis,
        @DefaultValue("2000") int kafkaRequestMillis,
        @DefaultValue("5000") int kafkaDeliveryMillis,
        @DefaultValue("5") int kafkaLingerMillis,
        @DefaultValue("8388608") int kafkaBufferBytes,
        @DefaultValue("2") int kafkaCloseSeconds,
        @DefaultValue("3") int otlpTimeoutSeconds,
        @DefaultValue("2048") int otlpQueue,
        @DefaultValue("200") int otlpDelayMillis,
        @DefaultValue("8000") int kafkaAckMillis) {
    public TraceDeliveryProperties {
        if (kafkaDeliveryMillis < kafkaRequestMillis + (long)kafkaLingerMillis) throw new IllegalArgumentException("Invalid Kafka timeout relationship");
        if (kafkaAckMillis < kafkaDeliveryMillis || kafkaAckMillis > 600000) throw new IllegalArgumentException("Kafka acknowledgement must cover the delivery timeout");
        if (kafkaMaxInFlight < 1 || kafkaMaxInFlight > 5) throw new IllegalArgumentException("Invalid kafkaMaxInFlight");
        if (kafkaMaxBlockMillis < 1 || kafkaMaxBlockMillis > 600000) throw new IllegalArgumentException("Invalid kafkaMaxBlockMillis");
        if (kafkaRequestMillis < 1 || kafkaRequestMillis > 600000) throw new IllegalArgumentException("Invalid kafkaRequestMillis");
        if (kafkaDeliveryMillis < 1 || kafkaDeliveryMillis > 600000) throw new IllegalArgumentException("Invalid kafkaDeliveryMillis");
        if (kafkaLingerMillis < 0 || kafkaLingerMillis > 60000) throw new IllegalArgumentException("Invalid kafkaLingerMillis");
        if (kafkaBufferBytes < 65536 || kafkaBufferBytes > 67108864) throw new IllegalArgumentException("Invalid kafkaBufferBytes");
        if (kafkaCloseSeconds < 0 || kafkaCloseSeconds > 600) throw new IllegalArgumentException("Invalid kafkaCloseSeconds");
        if (otlpTimeoutSeconds < 1 || otlpTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid otlpTimeoutSeconds");
        if (otlpQueue < 1 || otlpQueue > 100000) throw new IllegalArgumentException("Invalid otlpQueue");
        if (otlpDelayMillis < 1 || otlpDelayMillis > 60000) throw new IllegalArgumentException("Invalid otlpDelayMillis");
        if (initialDelayMillis < 0 || initialDelayMillis > 60000) throw new IllegalArgumentException("Invalid harness.trace.delivery.initial-delay-millis");
        if (pollMillis < 1 || pollMillis > 60000) throw new IllegalArgumentException("Invalid harness.trace.delivery.poll-millis");
        if (maxBatch < 1 || maxBatch > 10000) throw new IllegalArgumentException("Invalid harness.trace.delivery.max-batch");
        if (retryBaseMillis < 1 || retryBaseMillis > 60000) throw new IllegalArgumentException("Invalid harness.trace.delivery.retry-base-millis");
        if (retryCapMillis < 1 || retryCapMillis > 3600000) throw new IllegalArgumentException("Invalid harness.trace.delivery.retry-cap-millis");
        if (retryJitterMillis < 1 || retryJitterMillis > 60000) throw new IllegalArgumentException("Invalid harness.trace.delivery.retry-jitter-millis");
        if (warningMillis < 1 || warningMillis > 3600000) throw new IllegalArgumentException("Invalid harness.trace.delivery.warning-millis");
        if (closeWaitSeconds < 0 || closeWaitSeconds > 600) throw new IllegalArgumentException("Invalid harness.trace.delivery.close-wait-seconds");
        if (forcedCloseWaitSeconds < 0 || forcedCloseWaitSeconds > 600) throw new IllegalArgumentException("Invalid harness.trace.delivery.forced-close-wait-seconds");
        if (queryParallelism < 1 || queryParallelism > 128) throw new IllegalArgumentException("Invalid harness.trace.delivery.query-parallelism");
        if (queryMaxSpans < 1 || queryMaxSpans > 100000) throw new IllegalArgumentException("Invalid harness.trace.delivery.query-max-spans");
        if (queryMaxBytes < 1024 || queryMaxBytes > 67108864) throw new IllegalArgumentException("Invalid harness.trace.delivery.query-max-bytes");
        if (queryExecutionSeconds < 1 || queryExecutionSeconds > 600) throw new IllegalArgumentException("Invalid harness.trace.delivery.query-execution-seconds");
        if (queryConnectMillis < 1 || queryConnectMillis > 600000) throw new IllegalArgumentException("Invalid harness.trace.delivery.query-connect-millis");
        if (queryReadMillis < 1 || queryReadMillis > 600000) throw new IllegalArgumentException("Invalid harness.trace.delivery.query-read-millis");
        if (retryBaseMillis > retryCapMillis || queryExecutionSeconds * 1000L >= queryReadMillis) throw new IllegalArgumentException("Invalid trace retry or timeout relationship");
    }
    public static TraceDeliveryProperties defaults() { return new TraceDeliveryProperties(100, 200, 100, 250, 30000, 100, 10000, 10, 2, 4, 2000, 10485760, 5, 2000, 7000, 5, 1000, 2000, 5000, 5, 8388608, 2, 3, 2048, 200, 8000); }
}
