package com.tianmu.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Startup budgets, enforced by the host before exposing any private text. */
@ConfigurationProperties("agent.memory.recall")
public record MemoryRecallPolicy(@DefaultValue("20") int overviewMaxItems,
        @DefaultValue("8192") int overviewMaxBytes, @DefaultValue("200") int overviewScanLimit,
        @DefaultValue("3000") int timeoutMillis) {
    public MemoryRecallPolicy {
        if(overviewMaxItems<1 || overviewMaxItems>32 || overviewMaxBytes<2048 || overviewMaxBytes>32768
                || overviewScanLimit<overviewMaxItems || overviewScanLimit>1000 || timeoutMillis<100 || timeoutMillis>30000)
            throw new IllegalArgumentException("Invalid host memory recall budgets");
    }
    public static MemoryRecallPolicy defaults(){return new MemoryRecallPolicy(20,8192,200,3000);}
}
