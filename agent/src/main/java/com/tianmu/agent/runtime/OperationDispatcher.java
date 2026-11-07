package com.tianmu.agent.runtime;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(OperationPolicy.class)
public final class OperationDispatcher {
    private final RunService runs;private final OperationRegistry registry;
    public OperationDispatcher(RunService runs,OperationRegistry registry){this.runs=runs;this.registry=registry;}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelayString="${agent.operations.poll-millis:500}")
    public void tick(){registry.resume(runs.waiting());}
}
