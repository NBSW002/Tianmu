package com.tianmu.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.execution")
public record ExecutionProperties(@DefaultValue Pool runs, @DefaultValue Pool preparation,
        @DefaultValue Pool model, @DefaultValue Pool tools, @DefaultValue Pool video, @DefaultValue Pool sse,
        @DefaultValue Pool termination,
        @DefaultValue("1") int deadlineThreads,
        @DefaultValue("2") int maintenanceThreads,
        @DefaultValue("1") int memoryCore,
        @DefaultValue("1") int memoryMax,
        @DefaultValue("4") int memoryQueue,
        @DefaultValue("4") int toolBatchCore,
        @DefaultValue("8") int toolBatchMax,
        @DefaultValue("32") int toolBatchQueue,
        @DefaultValue("2") int shutdownWaitSeconds) {
    public ExecutionProperties {
        new Pool(memoryCore,memoryMax,memoryQueue);new Pool(toolBatchCore,toolBatchMax,toolBatchQueue);
        if (memoryCore < 1 || memoryCore > 128) throw new IllegalArgumentException("Invalid memoryCore");
        if (memoryMax < 1 || memoryMax > 128) throw new IllegalArgumentException("Invalid memoryMax");
        if (memoryQueue < 1 || memoryQueue > 4096) throw new IllegalArgumentException("Invalid memoryQueue");
        if (toolBatchCore < 1 || toolBatchCore > 128) throw new IllegalArgumentException("Invalid toolBatchCore");
        if (toolBatchMax < 1 || toolBatchMax > 128) throw new IllegalArgumentException("Invalid toolBatchMax");
        if (toolBatchQueue < 1 || toolBatchQueue > 4096) throw new IllegalArgumentException("Invalid toolBatchQueue");
        if (shutdownWaitSeconds < 0 || shutdownWaitSeconds > 600) throw new IllegalArgumentException("Invalid shutdownWaitSeconds");
        if (deadlineThreads < 1 || deadlineThreads > 128 || maintenanceThreads < 1 || maintenanceThreads > 128)
            throw new IllegalArgumentException("Invalid scheduler capacity");
    }
    public record Pool(@DefaultValue("2") int core, @DefaultValue("4") int max, @DefaultValue("32") int queue) {
        public Pool {
            if (core < 1 || max < core || max > 128 || queue < 1 || queue > 4096)
                throw new IllegalArgumentException("Invalid executor capacity");
        }
    }
}
