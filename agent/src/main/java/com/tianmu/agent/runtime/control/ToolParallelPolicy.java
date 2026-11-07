package com.tianmu.agent.runtime.control;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Startup-bound tool dispatch capacity; does not change actual-worker permit ownership. */
@ConfigurationProperties("agent.tools.parallel")
public record ToolParallelPolicy(@DefaultValue("false") boolean enabled, @DefaultValue("2") int perRun) {
    public ToolParallelPolicy {
        if (perRun < 1 || perRun > 4) throw new IllegalArgumentException("Invalid tool concurrency");
    }
}
