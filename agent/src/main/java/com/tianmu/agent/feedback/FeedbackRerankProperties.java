package com.tianmu.agent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.feedback.rerank")
public record FeedbackRerankProperties(@DefaultValue("false") boolean enabled,String endpoint,
        @DefaultValue("qwen3-rerank") String model,String apiKey,String apiKeyFile) {
    @Override public String toString(){return "FeedbackRerankProperties[enabled="+enabled+"]";}
}
