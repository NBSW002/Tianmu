package com.tianmu.agent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("agent.feedback")
public record FeedbackPolicy(@DefaultValue("false") boolean enabled, @DefaultValue("20000") int maxRecords,
        @DefaultValue("67108864") long maxSnapshotBytes, @DefaultValue("24000") int batchBytes,
        @DefaultValue("40") int batchGroups, @DefaultValue("8") int mergeFanIn,
        @DefaultValue("2000") int maxModelCalls, @DefaultValue("16000000") long maxTokens,
        @DefaultValue("1h") Duration timeout, @DefaultValue("30s") Duration modelTimeout) {
    public FeedbackPolicy {
        if(maxRecords<1 || maxRecords>100000 || maxSnapshotBytes<1048576 || maxSnapshotBytes>536870912L
                || batchBytes<24000 || batchBytes>48000 || batchGroups<1 || batchGroups>100 || mergeFanIn<2 || mergeFanIn>8
                || maxModelCalls<1 || maxModelCalls>10000 || maxTokens<8192 || maxTokens>1000000000L
                || timeout==null || timeout.compareTo(Duration.ofMinutes(1))<0 || timeout.compareTo(Duration.ofHours(6))>0
                || modelTimeout==null || modelTimeout.toSeconds()<1 || modelTimeout.toSeconds()>60)throw new IllegalArgumentException("Invalid feedback execution policy");
    }
    public static FeedbackPolicy defaults(){return new FeedbackPolicy(false,20000,67108864,24000,40,8,2000,16000000,Duration.ofHours(1),Duration.ofSeconds(30));}
}
