package com.tianmu.agent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("agent.feedback")
public record FeedbackPolicy(@DefaultValue("5000") int maxRecords,
        @DefaultValue("67108864") long maxSnapshotBytes, @DefaultValue("24000") int batchBytes,
        @DefaultValue("80") int batchGroups,
        @DefaultValue("2000") int maxModelCalls, @DefaultValue("16000000") long maxTokens,
        @DefaultValue("1h") Duration timeout, @DefaultValue("30s") Duration modelTimeout,@DefaultValue("1") int readParallelism) {
    public FeedbackPolicy(int records,long bytes,int batchBytes,int groups,int calls,long tokens,Duration timeout,Duration modelTimeout){this(records,bytes,batchBytes,groups,calls,tokens,timeout,modelTimeout,1);}
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public FeedbackPolicy {
        if(readParallelism<1 || readParallelism>4 || maxRecords<1 || maxRecords>100000 || maxSnapshotBytes<1048576 || maxSnapshotBytes>536870912L
                || batchBytes<24000 || batchBytes>48000 || batchGroups<1 || batchGroups>100
                || maxModelCalls<1 || maxModelCalls>10000 || maxTokens<8192 || maxTokens>1000000000L
                || timeout==null || timeout.compareTo(Duration.ofMinutes(1))<0 || timeout.compareTo(Duration.ofHours(6))>0
                || modelTimeout==null || modelTimeout.toSeconds()<1 || modelTimeout.toSeconds()>60)throw new IllegalArgumentException("Invalid feedback execution policy");
    }
    public static FeedbackPolicy defaults(){return new FeedbackPolicy(5000,67108864,24000,80,2000,16000000,Duration.ofHours(1),Duration.ofSeconds(30));}
}
