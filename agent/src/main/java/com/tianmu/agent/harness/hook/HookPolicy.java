package com.tianmu.agent.harness.hook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("harness.hooks")
public record HookPolicy(@DefaultValue("2") int gateWorkers,@DefaultValue("2") int observerWorkers,
        @DefaultValue("16") int queueCapacity,@DefaultValue("500") int gateTimeoutMillis,@DefaultValue("100") int observerTimeoutMillis,
        @DefaultValue("2") int transformWorkers,@DefaultValue("500") int transformTimeoutMillis) {
    public HookPolicy(int gates,int observers,int queue,int gateTimeout,int observerTimeout){this(gates,observers,queue,gateTimeout,observerTimeout,2,500);}
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public HookPolicy {
        if(gateWorkers<1 || gateWorkers>32 || observerWorkers<1 || observerWorkers>32 || queueCapacity<1 || queueCapacity>1024
                || gateTimeoutMillis<1 || gateTimeoutMillis>10000 || observerTimeoutMillis<1 || observerTimeoutMillis>10000
                || transformWorkers<1 || transformWorkers>32 || transformTimeoutMillis<1 || transformTimeoutMillis>10000)throw new IllegalArgumentException("Invalid hook execution policy");
    }
    public static HookPolicy defaults(){return new HookPolicy(2,2,16,500,100);}
}
