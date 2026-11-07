package com.tianmu.agent.adapters.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Module-owned startup policy. Defaults preserve the pre-governance execution values. */
@ConfigurationProperties("agent.storage.runtime")
public record StorageRuntimeProperties(@DefaultValue("5") int queryTimeoutSeconds,
        @DefaultValue("5") int transactionTimeoutSeconds,
        @DefaultValue("30") int runRetentionDays,
        @DefaultValue("7") int eventRetentionDays,
        @DefaultValue("90") int auditRetentionDays,
        @DefaultValue("24") int clarificationHours,
        @DefaultValue("200") int eventPageSize,
        @DefaultValue("32") int recoveryBatch,
        @DefaultValue("1000") int outboxPollMillis,
        @DefaultValue("20") int outboxBatch,
        @DefaultValue("15") int outboxLeaseSeconds,
        @DefaultValue("10") int outboxMaxAttempts,
        @DefaultValue("5") int outboxRetrySeconds,
        @DefaultValue("5000") int cleanupMillis,
        @DefaultValue("10") int cleanupSessionBatch,
        @DefaultValue("500") int cleanupEventBatch,
        @DefaultValue("100") int cleanupClarificationBatch,
        @DefaultValue("20") int cleanupRunBatch,
        @DefaultValue("1") int pendingArtifactHours,
        @DefaultValue("10") int conversationTurns,
        @DefaultValue("24") int conversationCacheHours,
        @DefaultValue("300") int redisTimeoutMillis,
        @DefaultValue("2") int redisRetrySeconds,
        @DefaultValue("8") int poolMax,
        @DefaultValue("1") int poolMin,
        @DefaultValue("2500") int poolAcquireMillis,
        @DefaultValue("1000") int poolValidationMillis,
        @DefaultValue("2000") int jdbcConnectMillis,
        @DefaultValue("5000") int jdbcSocketMillis) {
    public StorageRuntimeProperties {
        if (poolMin > poolMax || poolValidationMillis >= poolAcquireMillis) throw new IllegalArgumentException("Invalid storage pool relationship");
        if (poolMax < 1 || poolMax > 128) throw new IllegalArgumentException("Invalid poolMax");
        if (poolMin < 0 || poolMin > 128) throw new IllegalArgumentException("Invalid poolMin");
        if (poolAcquireMillis < 250 || poolAcquireMillis > 600000) throw new IllegalArgumentException("Invalid poolAcquireMillis");
        if (poolValidationMillis < 250 || poolValidationMillis > 600000) throw new IllegalArgumentException("Invalid poolValidationMillis");
        if (jdbcConnectMillis < 1 || jdbcConnectMillis > 600000) throw new IllegalArgumentException("Invalid jdbcConnectMillis");
        if (jdbcSocketMillis < 1 || jdbcSocketMillis > 600000) throw new IllegalArgumentException("Invalid jdbcSocketMillis");
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.storage.runtime.query-timeout-seconds");
        if (transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.storage.runtime.transaction-timeout-seconds");
        if (runRetentionDays < 1 || runRetentionDays > 3650) throw new IllegalArgumentException("Invalid agent.storage.runtime.run-retention-days");
        if (eventRetentionDays < 1 || eventRetentionDays > 3650) throw new IllegalArgumentException("Invalid agent.storage.runtime.event-retention-days");
        if (auditRetentionDays < 1 || auditRetentionDays > 3650) throw new IllegalArgumentException("Invalid agent.storage.runtime.audit-retention-days");
        if (clarificationHours < 1 || clarificationHours > 87600) throw new IllegalArgumentException("Invalid agent.storage.runtime.clarification-hours");
        if (eventPageSize < 1 || eventPageSize > 10000) throw new IllegalArgumentException("Invalid agent.storage.runtime.event-page-size");
        if (recoveryBatch < 1 || recoveryBatch > 10000) throw new IllegalArgumentException("Invalid agent.storage.runtime.recovery-batch");
        if (outboxPollMillis < 1 || outboxPollMillis > 3600000) throw new IllegalArgumentException("Invalid agent.storage.runtime.outbox-poll-millis");
        if (outboxBatch < 1 || outboxBatch > 10000) throw new IllegalArgumentException("Invalid agent.storage.runtime.outbox-batch");
        if (outboxLeaseSeconds < 1 || outboxLeaseSeconds > 3600) throw new IllegalArgumentException("Invalid agent.storage.runtime.outbox-lease-seconds");
        if (outboxMaxAttempts < 1 || outboxMaxAttempts > 100) throw new IllegalArgumentException("Invalid agent.storage.runtime.outbox-max-attempts");
        if (outboxRetrySeconds < 0 || outboxRetrySeconds > 3600) throw new IllegalArgumentException("Invalid agent.storage.runtime.outbox-retry-seconds");
        if (cleanupMillis < 1 || cleanupMillis > 3600000) throw new IllegalArgumentException("Invalid agent.storage.runtime.cleanup-millis");
        if (cleanupSessionBatch < 1 || cleanupSessionBatch > 10000) throw new IllegalArgumentException("Invalid agent.storage.runtime.cleanup-session-batch");
        if (cleanupEventBatch < 1 || cleanupEventBatch > 10000) throw new IllegalArgumentException("Invalid agent.storage.runtime.cleanup-event-batch");
        if (cleanupClarificationBatch < 1 || cleanupClarificationBatch > 10000) throw new IllegalArgumentException("Invalid agent.storage.runtime.cleanup-clarification-batch");
        if (cleanupRunBatch < 1 || cleanupRunBatch > 10000) throw new IllegalArgumentException("Invalid agent.storage.runtime.cleanup-run-batch");
        if (pendingArtifactHours < 1 || pendingArtifactHours > 87600) throw new IllegalArgumentException("Invalid agent.storage.runtime.pending-artifact-hours");
        if (conversationTurns < 1 || conversationTurns > 1000) throw new IllegalArgumentException("Invalid agent.storage.runtime.conversation-turns");
        if (conversationCacheHours < 1 || conversationCacheHours > 87600) throw new IllegalArgumentException("Invalid agent.storage.runtime.conversation-cache-hours");
        if (redisTimeoutMillis < 1 || redisTimeoutMillis > 60000) throw new IllegalArgumentException("Invalid agent.storage.runtime.redis-timeout-millis");
        if (redisRetrySeconds < 0 || redisRetrySeconds > 3600) throw new IllegalArgumentException("Invalid agent.storage.runtime.redis-retry-seconds");
    }
    public static StorageRuntimeProperties defaults() { return new StorageRuntimeProperties(5, 5, 30, 7, 90, 24, 200, 32, 1000, 20, 15, 10, 5, 5000, 10, 500, 100, 20, 1, 10, 24, 300, 2, 8, 1, 2500, 1000, 2000, 5000); }
}
