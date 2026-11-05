package com.tianmu.agent.feedback;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.feedback.reuse")
public record FeedbackReusePolicy(@DefaultValue("true") boolean enabled,@DefaultValue("10m") Duration window,@DefaultValue("1024") int maxEntries){
    public FeedbackReusePolicy{if(window==null || window.isNegative() || window.compareTo(Duration.ofDays(7))>0 || maxEntries<1 || maxEntries>8192)throw new IllegalArgumentException("Invalid analysis reuse limits");}
    public static FeedbackReusePolicy defaults(){return new FeedbackReusePolicy(true,Duration.ofMinutes(10),1024);}
}
