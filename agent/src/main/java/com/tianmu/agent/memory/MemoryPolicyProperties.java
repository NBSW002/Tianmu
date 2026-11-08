package com.tianmu.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Startup policy for incremental learning, maintenance and bounded recall. */
@ConfigurationProperties("agent.memory.policy")
public record MemoryPolicyProperties(
        @DefaultValue("3000") int debounceMillis,
        @DefaultValue("30000") int maxWaitMillis,
        @DefaultValue("8") int batchMessages,
        @DefaultValue("24") int dreamIntervalHours,
        @DefaultValue("5") int dreamMinChanges,
        @DefaultValue("40") int dreamMaxItems,
        @DefaultValue("3") int coreMaxItems,
        @DefaultValue("1536") int coreMaxBytes,
        @DefaultValue("2") int queryHistoryMessages) {
    public MemoryPolicyProperties {
        if(debounceMillis<0 || maxWaitMillis<debounceMillis || maxWaitMillis>3600000)throw new IllegalArgumentException("Invalid memory debounce/max-wait millis");
        if(batchMessages<1 || batchMessages>32)throw new IllegalArgumentException("Invalid memory batch-messages");
        if(dreamIntervalHours<1 || dreamIntervalHours>8760 || dreamMinChanges<1 || dreamMinChanges>1000 || dreamMaxItems<2 || dreamMaxItems>50)throw new IllegalArgumentException("Invalid memory dream policy");
        if(coreMaxItems<0 || coreMaxItems>12 || coreMaxBytes<0 || coreMaxBytes>6144 || queryHistoryMessages<0 || queryHistoryMessages>4)throw new IllegalArgumentException("Invalid memory recall layers");
    }
    public static MemoryPolicyProperties defaults(){return new MemoryPolicyProperties(3000,30000,8,24,5,40,3,1536,2);}
}
