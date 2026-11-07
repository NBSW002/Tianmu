package com.tianmu.agent.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Byte limits are deterministic; they are not a provider-specific token count. */
@ConfigurationProperties("agent.context")
public record ContextPolicy(@DefaultValue("98304") int maxInputBytes,
                            @DefaultValue("16384") int maxHistoryBytes,
                            @DefaultValue("4") int recentMaxMessages,
                            @DefaultValue("8192") int recentMaxBytes) {
    public ContextPolicy(int maxInputBytes, int maxHistoryBytes) {
        this(maxInputBytes, maxHistoryBytes, 4, 8192);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ContextPolicy {
        if (maxInputBytes < 1024 || maxInputBytes > 4_194_304
                || maxHistoryBytes < 0 || maxHistoryBytes > maxInputBytes
                || recentMaxMessages < 0 || recentMaxMessages > 1000
                || recentMaxBytes < 0 || recentMaxBytes > 4_194_304)
            throw new IllegalArgumentException("Invalid model context limits");
    }
    public static ContextPolicy defaults() { return new ContextPolicy(98304, 16384); }
}
