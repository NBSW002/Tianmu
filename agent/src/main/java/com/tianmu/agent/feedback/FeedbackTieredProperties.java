package com.tianmu.agent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Startup-only routing policy, included in the material protocol revision. */
@ConfigurationProperties("agent.feedback.tiered")
public record FeedbackTieredProperties(@DefaultValue("true") boolean enabled,
                                      @DefaultValue("32768") int directInputBytes) {
    public FeedbackTieredProperties {
        if (directInputBytes < 8192 || directInputBytes > 65536) {
            throw new IllegalArgumentException("Direct input budget must be 8192..65536 bytes");
        }
    }
}
