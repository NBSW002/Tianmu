package com.tianmu.agent.video;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.video.cache")
public record VideoCachePolicy(@DefaultValue("1h") Duration memoryTtl,@DefaultValue("0s") Duration retention,@DefaultValue("1024") int maxEntries){
    public VideoCachePolicy{if(memoryTtl==null || memoryTtl.isZero() || memoryTtl.isNegative() || memoryTtl.compareTo(Duration.ofDays(1))>0 || retention==null || retention.isNegative() || retention.compareTo(Duration.ofDays(3650))>0 || maxEntries<1 || maxEntries>8192)throw new IllegalArgumentException("Invalid video asset cache limits");}
    public static VideoCachePolicy defaults(){return new VideoCachePolicy(Duration.ofHours(1),Duration.ZERO,1024);}
}
