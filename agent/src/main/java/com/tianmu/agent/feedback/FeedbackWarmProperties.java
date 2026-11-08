package com.tianmu.agent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.feedback.warm")
public record FeedbackWarmProperties(String businessUrl,String tokenFile,@DefaultValue("5000") int pollMillis,@DefaultValue("600") int timeoutSeconds,@DefaultValue("1500") int maxEmbeddingCalls){
    public FeedbackWarmProperties{
        if(pollMillis<1000 || pollMillis>60000 || timeoutSeconds<30 || timeoutSeconds>600 || maxEmbeddingCalls<1 || maxEmbeddingCalls>1500)throw new IllegalArgumentException("Invalid feedback warm limits");
    }
}
