package com.tianmu.agent.config;

@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
@org.springframework.boot.context.properties.EnableConfigurationProperties({
        com.tianmu.agent.memory.CompactionProperties.class,
        com.tianmu.agent.memory.MemoryRuntimeProperties.class,
        com.tianmu.agent.memory.MemoryPolicyProperties.class,
        com.tianmu.agent.feedback.FeedbackRuntimeProperties.class,
        com.tianmu.agent.video.VideoRuntimeProperties.class,
        com.tianmu.agent.adapters.storage.StorageRuntimeProperties.class,
        com.tianmu.agent.harness.trace.TraceDeliveryProperties.class})
public class GovernanceConfiguration {}
