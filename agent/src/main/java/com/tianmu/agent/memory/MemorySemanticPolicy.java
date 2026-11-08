package com.tianmu.agent.memory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.memory.semantic")
public record MemorySemanticPolicy(@DefaultValue("5000") int pollMillis,@DefaultValue("20") int batchItems,
        @DefaultValue("800") int queryTimeoutMillis,@DefaultValue("60") int retrySeconds,
        @DefaultValue("20") int candidates,@DefaultValue("0.45") double minSimilarity,
        @DefaultValue("30") int batchTimeoutSeconds){
    public MemorySemanticPolicy {
        if(pollMillis<100 || pollMillis>3600000 || batchItems<1 || batchItems>20 || queryTimeoutMillis<100 || queryTimeoutMillis>5000
                || retrySeconds<1 || retrySeconds>3600 || candidates<1 || candidates>100 || !Double.isFinite(minSimilarity) || minSimilarity<0 || minSimilarity>1 || batchTimeoutSeconds<1 || batchTimeoutSeconds>120)
            throw new IllegalArgumentException("Invalid memory semantic retrieval policy");
    }
}
