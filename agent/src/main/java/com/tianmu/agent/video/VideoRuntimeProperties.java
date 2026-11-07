package com.tianmu.agent.video;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Module-owned startup policy. Defaults preserve the pre-governance execution values. */
@ConfigurationProperties("agent.video.runtime")
public record VideoRuntimeProperties(@DefaultValue("2") int indexWorkers,
        @DefaultValue("16") int indexQueue,
        @DefaultValue("2") int perUserActive,
        @DefaultValue("3600000") int maxDurationMillis,
        @DefaultValue("120000") int segmentMillis,
        @DefaultValue("1800") int indexTimeoutSeconds,
        @DefaultValue("120") int indexMaxCalls,
        @DefaultValue("250000") int indexMaxTokens,
        @DefaultValue("8192") int tokenReservation,
        @DefaultValue("15") int sourceTimeoutSeconds,
        @DefaultValue("10000") int heartbeatMillis,
        @DefaultValue("120") int staleAfterSeconds,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("5") int casAttempts,
        @DefaultValue("128") int localCacheCap,
        @DefaultValue("2") int storageRetrySeconds,
        @DefaultValue("5") int queryTimeoutSeconds,
        @DefaultValue("100") int cacheCleanupBatch,
        @DefaultValue("3") int modelConnectSeconds,
        @DefaultValue("60000") int modelTimeoutMillis,
        @DefaultValue("128") int flightCapacity,
        @DefaultValue("50") int flightPollMillis) {
    public VideoRuntimeProperties {
        if (indexWorkers < 1 || indexWorkers > 128) throw new IllegalArgumentException("Invalid agent.video.runtime.index-workers");
        if (indexQueue < 1 || indexQueue > 4096) throw new IllegalArgumentException("Invalid agent.video.runtime.index-queue");
        if (perUserActive < 1 || perUserActive > 1000) throw new IllegalArgumentException("Invalid agent.video.runtime.per-user-active");
        if (maxDurationMillis < 120000 || maxDurationMillis > 3600000) throw new IllegalArgumentException("Invalid agent.video.runtime.max-duration-millis");
        if (segmentMillis < 1 || segmentMillis > 120000) throw new IllegalArgumentException("Invalid agent.video.runtime.segment-millis");
        if (indexTimeoutSeconds < 1 || indexTimeoutSeconds > 21600) throw new IllegalArgumentException("Invalid agent.video.runtime.index-timeout-seconds");
        if (indexMaxCalls < 1 || indexMaxCalls > 10000) throw new IllegalArgumentException("Invalid agent.video.runtime.index-max-calls");
        if (indexMaxTokens < 8192 || indexMaxTokens > 1000000000) throw new IllegalArgumentException("Invalid agent.video.runtime.index-max-tokens");
        if (tokenReservation < 1 || tokenReservation > 1000000000) throw new IllegalArgumentException("Invalid agent.video.runtime.token-reservation");
        if (sourceTimeoutSeconds < 1 || sourceTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.video.runtime.source-timeout-seconds");
        if (heartbeatMillis < 1 || heartbeatMillis > 3600000) throw new IllegalArgumentException("Invalid agent.video.runtime.heartbeat-millis");
        if (staleAfterSeconds < 1 || staleAfterSeconds > 3600) throw new IllegalArgumentException("Invalid agent.video.runtime.stale-after-seconds");
        if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("Invalid agent.video.runtime.max-attempts");
        if (casAttempts < 1 || casAttempts > 100) throw new IllegalArgumentException("Invalid agent.video.runtime.cas-attempts");
        if (localCacheCap < 1 || localCacheCap > 8192) throw new IllegalArgumentException("Invalid agent.video.runtime.local-cache-cap");
        if (storageRetrySeconds < 0 || storageRetrySeconds > 3600) throw new IllegalArgumentException("Invalid agent.video.runtime.storage-retry-seconds");
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 600) throw new IllegalArgumentException("Invalid agent.video.runtime.query-timeout-seconds");
        if (cacheCleanupBatch < 1 || cacheCleanupBatch > 10000) throw new IllegalArgumentException("Invalid agent.video.runtime.cache-cleanup-batch");
        if (modelConnectSeconds < 1 || modelConnectSeconds > 30) throw new IllegalArgumentException("Invalid agent.video.runtime.model-connect-seconds");
        if (modelTimeoutMillis < 1 || modelTimeoutMillis > 600000) throw new IllegalArgumentException("Invalid agent.video.runtime.model-timeout-millis");
        if (flightCapacity < 1 || flightCapacity > 4096) throw new IllegalArgumentException("Invalid agent.video.runtime.flight-capacity");
        if (flightPollMillis < 1 || flightPollMillis > 60000) throw new IllegalArgumentException("Invalid agent.video.runtime.flight-poll-millis");
        if (heartbeatMillis >= staleAfterSeconds * 1000L || tokenReservation > indexMaxTokens) throw new IllegalArgumentException("Invalid video lease or budget relationship");
    }
    public static VideoRuntimeProperties defaults() { return new VideoRuntimeProperties(2, 16, 2, 3600000, 120000, 1800, 120, 250000, 8192, 15, 10000, 120, 2, 5, 128, 2, 5, 100, 3, 60000, 128, 50); }
}
