package com.tianmu.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Module-owned startup policy. Defaults preserve the pre-governance execution values. */
@ConfigurationProperties("agent.memory.runtime")
public record MemoryRuntimeProperties(@DefaultValue("2000") int pollMillis,
        @DefaultValue("10000") int heartbeatMillis,
        @DefaultValue("30") int leaseSeconds,
        @DefaultValue("5") int expiredMaxAttempts,
        @DefaultValue("4") int failureMaxAttempts,
        @DefaultValue("10") int retryDelaySeconds,
        @DefaultValue("15") int extractionTimeoutSeconds,
        @DefaultValue("8192") int extractionMaxTokens,
        @DefaultValue("4096") int extractionReservation,
        @DefaultValue("1024") int extractionOutputTokens,
        @DefaultValue("30") int consolidationTimeoutSeconds,
        @DefaultValue("16384") int consolidationMaxTokens,
        @DefaultValue("4096") int consolidationReservation,
        @DefaultValue("3072") int consolidationOutputTokens,
        @DefaultValue("10") int sourceSequenceWindow,
        @DefaultValue("32768") int sourceMaxBytes,
        @DefaultValue("6") int coreCandidates,
        @DefaultValue("16") int queryCandidates,
        @DefaultValue("12") int scopedCandidates,
        @DefaultValue("12") int maxRecallItems,
        @DefaultValue("6144") int maxRecallBytes,
        @DefaultValue("100") int guardIntervalMillis,
        @DefaultValue("40") int candidateFetchLimit,
        @DefaultValue("100") int topicListLimit,
        @DefaultValue("3") int queryTimeoutSeconds,
        @DefaultValue("5") int transactionTimeoutSeconds,
        @DefaultValue("5") int personalQueryTimeoutSeconds,
        @DefaultValue("5") int personalTransactionTimeoutSeconds,
        @DefaultValue("1000") int personalMaxItems) {
    public MemoryRuntimeProperties {
        if (pollMillis < 1 || pollMillis > 3600000) throw new IllegalArgumentException("Invalid agent.memory.runtime.poll-millis");
        if (heartbeatMillis < 1 || heartbeatMillis > 3600000) throw new IllegalArgumentException("Invalid agent.memory.runtime.heartbeat-millis");
        if (leaseSeconds < 1 || leaseSeconds > 3600) throw new IllegalArgumentException("Invalid agent.memory.runtime.lease-seconds");
        if (expiredMaxAttempts < 1 || expiredMaxAttempts > 100) throw new IllegalArgumentException("Invalid agent.memory.runtime.expired-max-attempts");
        if (failureMaxAttempts < 1 || failureMaxAttempts > 100) throw new IllegalArgumentException("Invalid agent.memory.runtime.failure-max-attempts");
        if (retryDelaySeconds < 0 || retryDelaySeconds > 3600) throw new IllegalArgumentException("Invalid agent.memory.runtime.retry-delay-seconds");
        if (extractionTimeoutSeconds < 1 || extractionTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.memory.runtime.extraction-timeout-seconds");
        if (extractionMaxTokens < 4096 || extractionMaxTokens > 1000000000) throw new IllegalArgumentException("Invalid agent.memory.runtime.extraction-max-tokens");
        if (extractionReservation < 1 || extractionReservation > 1000000000) throw new IllegalArgumentException("Invalid agent.memory.runtime.extraction-reservation");
        if (extractionOutputTokens < 1 || extractionOutputTokens > 65536) throw new IllegalArgumentException("Invalid agent.memory.runtime.extraction-output-tokens");
        if (consolidationTimeoutSeconds < 1 || consolidationTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.memory.runtime.consolidation-timeout-seconds");
        if (consolidationMaxTokens < 4096 || consolidationMaxTokens > 1000000000) throw new IllegalArgumentException("Invalid agent.memory.runtime.consolidation-max-tokens");
        if (consolidationReservation < 1 || consolidationReservation > 1000000000) throw new IllegalArgumentException("Invalid agent.memory.runtime.consolidation-reservation");
        if (consolidationOutputTokens < 1 || consolidationOutputTokens > 65536) throw new IllegalArgumentException("Invalid agent.memory.runtime.consolidation-output-tokens");
        if (sourceSequenceWindow < 0 || sourceSequenceWindow > 10000) throw new IllegalArgumentException("Invalid agent.memory.runtime.source-sequence-window");
        if (sourceMaxBytes < 1 || sourceMaxBytes > 4194304) throw new IllegalArgumentException("Invalid agent.memory.runtime.source-max-bytes");
        if (coreCandidates < 1 || coreCandidates > 1000) throw new IllegalArgumentException("Invalid agent.memory.runtime.core-candidates");
        if (queryCandidates < 1 || queryCandidates > 1000) throw new IllegalArgumentException("Invalid agent.memory.runtime.query-candidates");
        if (scopedCandidates < 1 || scopedCandidates > 1000) throw new IllegalArgumentException("Invalid agent.memory.runtime.scoped-candidates");
        if (maxRecallItems < 1 || maxRecallItems > 32) throw new IllegalArgumentException("Invalid agent.memory.runtime.max-recall-items");
        if (maxRecallBytes < 1 || maxRecallBytes > 4194304) throw new IllegalArgumentException("Invalid agent.memory.runtime.max-recall-bytes");
        if (guardIntervalMillis < 0 || guardIntervalMillis > 60000) throw new IllegalArgumentException("Invalid agent.memory.runtime.guard-interval-millis");
        if (candidateFetchLimit < 1 || candidateFetchLimit > 1000) throw new IllegalArgumentException("Invalid agent.memory.runtime.candidate-fetch-limit");
        if (topicListLimit < 1 || topicListLimit > 10000) throw new IllegalArgumentException("Invalid agent.memory.runtime.topic-list-limit");
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.memory.runtime.query-timeout-seconds");
        if (transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.memory.runtime.transaction-timeout-seconds");
        if (personalQueryTimeoutSeconds < 1 || personalQueryTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.memory.runtime.personal-query-timeout-seconds");
        if (personalTransactionTimeoutSeconds < 1 || personalTransactionTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.memory.runtime.personal-transaction-timeout-seconds");
        if (personalMaxItems < 1 || personalMaxItems > 1000) throw new IllegalArgumentException("Invalid agent.memory.runtime.personal-max-items");
        if (heartbeatMillis >= leaseSeconds * 1000L || extractionReservation > extractionMaxTokens || consolidationReservation > consolidationMaxTokens || extractionOutputTokens > extractionMaxTokens || consolidationOutputTokens > consolidationMaxTokens) throw new IllegalArgumentException("Invalid memory lease or budget relationship");
    }
    public static MemoryRuntimeProperties defaults() { return new MemoryRuntimeProperties(2000, 10000, 30, 5, 4, 10, 15, 8192, 4096, 1024, 30, 16384, 4096, 3072, 10, 32768, 6, 16, 12, 12, 6144, 100, 40, 100, 3, 5, 5, 5, 1000); }
}
