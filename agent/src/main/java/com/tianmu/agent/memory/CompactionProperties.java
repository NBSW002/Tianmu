package com.tianmu.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Module-owned startup policy. Defaults preserve the pre-governance execution values. */
@ConfigurationProperties("agent.compaction")
public record CompactionProperties(@DefaultValue("5632") int triggerBytes,
        @DefaultValue("32768") int maxInputBytes,
        @DefaultValue("25") int modelTimeoutSeconds,
        @DefaultValue("65536") int maxTokens,
        @DefaultValue("8192") int tokenReservation,
        @DefaultValue("2048") int outputTokens,
        @DefaultValue("3500") int summaryMaxBytes,
        @DefaultValue("4") int minMessages,
        @DefaultValue("2") int retainRecentTurns,
        @DefaultValue("200") int sourcePageSize,
        @DefaultValue("16") int maxSegments,
        @DefaultValue("4096") int contextMaxBytes,
        @DefaultValue("3") int queryTimeoutSeconds,
        @DefaultValue("5") int transactionTimeoutSeconds) {
    public CompactionProperties {
        if (triggerBytes < 512 || triggerBytes > 32768) throw new IllegalArgumentException("Invalid agent.compaction.trigger-bytes");
        if (maxInputBytes < 512 || maxInputBytes > 4194304) throw new IllegalArgumentException("Invalid agent.compaction.max-input-bytes");
        if (modelTimeoutSeconds < 1 || modelTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.compaction.model-timeout-seconds");
        if (maxTokens < 8192 || maxTokens > 1000000000) throw new IllegalArgumentException("Invalid agent.compaction.max-tokens");
        if (tokenReservation < 1 || tokenReservation > 1000000000) throw new IllegalArgumentException("Invalid agent.compaction.token-reservation");
        if (outputTokens < 1 || outputTokens > 65536) throw new IllegalArgumentException("Invalid agent.compaction.output-tokens");
        if (summaryMaxBytes < 1 || summaryMaxBytes > 32768) throw new IllegalArgumentException("Invalid agent.compaction.summary-max-bytes");
        if (minMessages < 4 || minMessages > 1000) throw new IllegalArgumentException("Invalid agent.compaction.min-messages");
        if (retainRecentTurns < 0 || retainRecentTurns > 1000) throw new IllegalArgumentException("Invalid agent.compaction.retain-recent-turns");
        if (sourcePageSize < 4 || sourcePageSize > 10000) throw new IllegalArgumentException("Invalid agent.compaction.source-page-size");
        if (maxSegments < 1 || maxSegments > 1024) throw new IllegalArgumentException("Invalid agent.compaction.max-segments");
        if (contextMaxBytes < 1 || contextMaxBytes > 4194304) throw new IllegalArgumentException("Invalid agent.compaction.context-max-bytes");
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.compaction.query-timeout-seconds");
        if (transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.compaction.transaction-timeout-seconds");
        if (maxInputBytes < triggerBytes || sourcePageSize < minMessages || summaryMaxBytes >= maxInputBytes || tokenReservation > maxTokens || outputTokens > maxTokens) throw new IllegalArgumentException("Invalid compaction budget relationship");
    }
    public static CompactionProperties defaults() { return new CompactionProperties(5632, 32768, 25, 65536, 8192, 2048, 3500, 4, 2, 200, 16, 4096, 3, 5); }
    public CompactionProperties withTriggerBytes(int value) { return new CompactionProperties(value, maxInputBytes, modelTimeoutSeconds, maxTokens, tokenReservation, outputTokens, summaryMaxBytes, minMessages, retainRecentTurns, sourcePageSize, maxSegments, contextMaxBytes, queryTimeoutSeconds, transactionTimeoutSeconds); }
}
