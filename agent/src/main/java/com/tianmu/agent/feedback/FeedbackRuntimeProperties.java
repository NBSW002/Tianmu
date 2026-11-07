package com.tianmu.agent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Module-owned startup policy. Defaults preserve the pre-governance execution values. */
@ConfigurationProperties("agent.feedback.runtime")
public record FeedbackRuntimeProperties(@DefaultValue("90") int exportTimeoutSeconds,
        @DefaultValue("60") int snapshotLeaseSeconds,
        @DefaultValue("30") int modelLeaseMarginSeconds,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2") int retryStepSeconds,
        @DefaultValue("30") int retryMaxDelaySeconds,
        @DefaultValue("600") int preparationLeaseSeconds,
        @DefaultValue("2") int preparationWorkers,
        @DefaultValue("4") int preparationQueue,
        @DefaultValue("30") int workerKeepAliveSeconds,
        @DefaultValue("500") int pollMillis,
        @DefaultValue("8192") int tokenReservation,
        @DefaultValue("32") int topicDirectories,
        @DefaultValue("3600") int topicIdleSeconds,
        @DefaultValue("5000") int objectCleanupMillis,
        @DefaultValue("100") int objectCleanupBatch,
        @DefaultValue("5") int queryTimeoutSeconds,
        @DefaultValue("5") int transactionTimeoutSeconds,
        @DefaultValue("30") int objectRetentionDays,
        @DefaultValue("5") int cacheFlightMarginSeconds,
        @DefaultValue("100") int cacheCleanupBatch) {
    public FeedbackRuntimeProperties {
        if (objectRetentionDays < 1 || objectRetentionDays > 3650) throw new IllegalArgumentException("Invalid objectRetentionDays");
        if (cacheFlightMarginSeconds < 0 || cacheFlightMarginSeconds > 3600) throw new IllegalArgumentException("Invalid cacheFlightMarginSeconds");
        if (cacheCleanupBatch < 1 || cacheCleanupBatch > 10000) throw new IllegalArgumentException("Invalid cacheCleanupBatch");
        if (exportTimeoutSeconds < 1 || exportTimeoutSeconds > 3600) throw new IllegalArgumentException("Invalid agent.feedback.runtime.export-timeout-seconds");
        if (snapshotLeaseSeconds < 1 || snapshotLeaseSeconds > 3600) throw new IllegalArgumentException("Invalid agent.feedback.runtime.snapshot-lease-seconds");
        if (modelLeaseMarginSeconds < 1 || modelLeaseMarginSeconds > 3600) throw new IllegalArgumentException("Invalid agent.feedback.runtime.model-lease-margin-seconds");
        if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("Invalid agent.feedback.runtime.max-attempts");
        if (retryStepSeconds < 0 || retryStepSeconds > 3600) throw new IllegalArgumentException("Invalid agent.feedback.runtime.retry-step-seconds");
        if (retryMaxDelaySeconds < 0 || retryMaxDelaySeconds > 3600) throw new IllegalArgumentException("Invalid agent.feedback.runtime.retry-max-delay-seconds");
        if (preparationLeaseSeconds < 1 || preparationLeaseSeconds > 3600) throw new IllegalArgumentException("Invalid agent.feedback.runtime.preparation-lease-seconds");
        if (preparationWorkers < 1 || preparationWorkers > 128) throw new IllegalArgumentException("Invalid agent.feedback.runtime.preparation-workers");
        if (preparationQueue < 1 || preparationQueue > 4096) throw new IllegalArgumentException("Invalid agent.feedback.runtime.preparation-queue");
        if (workerKeepAliveSeconds < 0 || workerKeepAliveSeconds > 3600) throw new IllegalArgumentException("Invalid agent.feedback.runtime.worker-keep-alive-seconds");
        if (pollMillis < 1 || pollMillis > 3600000) throw new IllegalArgumentException("Invalid agent.feedback.runtime.poll-millis");
        if (tokenReservation < 1 || tokenReservation > 1000000000) throw new IllegalArgumentException("Invalid agent.feedback.runtime.token-reservation");
        if (topicDirectories < 1 || topicDirectories > 8192) throw new IllegalArgumentException("Invalid agent.feedback.runtime.topic-directories");
        if (topicIdleSeconds < 1 || topicIdleSeconds > 86400) throw new IllegalArgumentException("Invalid agent.feedback.runtime.topic-idle-seconds");
        if (objectCleanupMillis < 1 || objectCleanupMillis > 3600000) throw new IllegalArgumentException("Invalid agent.feedback.runtime.object-cleanup-millis");
        if (objectCleanupBatch < 1 || objectCleanupBatch > 10000) throw new IllegalArgumentException("Invalid agent.feedback.runtime.object-cleanup-batch");
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.feedback.runtime.query-timeout-seconds");
        if (transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.feedback.runtime.transaction-timeout-seconds");
    }
    public static FeedbackRuntimeProperties defaults() { return new FeedbackRuntimeProperties(90, 60, 30, 3, 2, 30, 600, 2, 4, 30, 500, 8192, 32, 3600, 5000, 100, 5, 5, 30, 5, 100); }
}
