package com.tianmu.agent.config;

/** Reader is always available. Enable the writer only after a compatible reader has been released. */
@org.springframework.boot.context.properties.ConfigurationProperties("agent.policy-snapshot")
public record PolicySnapshotProperties(@org.springframework.boot.context.properties.bind.DefaultValue("false") boolean enabled) {}
