package com.tianmu.agent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.operations")
public record OperationPolicy(@DefaultValue("500") int pollMillis) {
    public OperationPolicy { if(pollMillis<1 || pollMillis>3600000)throw new IllegalArgumentException("Invalid operation polling interval"); }
}
