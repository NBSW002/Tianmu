package com.tianmu.agent.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.*;
import java.util.List;

@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfiguration {
    public static ThreadPoolTaskExecutor standalone(String name, int core, int max, int queue) {
        var executor = configured(name, core, max, queue); executor.initialize(); return executor;
    }
    private static ThreadPoolTaskExecutor configured(String name,int core,int max,int queue){return configured(name,core,max,queue,2);}
    private static ThreadPoolTaskExecutor configured(String name, int core, int max, int queue,int shutdownWaitSeconds) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core); executor.setMaxPoolSize(max); executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(name + "-"); executor.setDaemon(true);
        executor.setAwaitTerminationSeconds(shutdownWaitSeconds); return executor;
    }
    private ThreadPoolTaskExecutor managed(String name, ExecutionProperties.Pool pool, MeterRegistry metrics,int shutdownWaitSeconds) {
        var executor = configured(name, pool.core(), pool.max(), pool.queue(),shutdownWaitSeconds);
        var rejected = metrics.counter("agent.executor.rejected", "pool", name);
        executor.setRejectedExecutionHandler((task, target) -> {
            rejected.increment(); throw new java.util.concurrent.RejectedExecutionException("Executor capacity exhausted");
        });
        return executor;
    }
    @Bean org.springframework.beans.factory.SmartInitializingSingleton executorMetrics(java.util.Map<String, ThreadPoolTaskExecutor> executors, MeterRegistry metrics) {
        return () -> executors.forEach((name, executor) -> ExecutorServiceMetrics.monitor(metrics, executor.getThreadPoolExecutor(), name, List.of()));
    }
    @Bean("runExecutor") ThreadPoolTaskExecutor runs(ExecutionProperties p, MeterRegistry m) { return managed("agent-runs", p.runs(), m,p.shutdownWaitSeconds()); }
    @Bean("preparationExecutor") ThreadPoolTaskExecutor preparation(ExecutionProperties p, MeterRegistry m) { return managed("agent-preparation", p.preparation(), m,p.shutdownWaitSeconds()); }
    @Bean("modelExecutor") ThreadPoolTaskExecutor model(ExecutionProperties p, MeterRegistry m) { return managed("agent-model", p.model(), m,p.shutdownWaitSeconds()); }
    @Bean("toolExecutor") ThreadPoolTaskExecutor tools(ExecutionProperties p, MeterRegistry m) { return managed("agent-tools", p.tools(), m,p.shutdownWaitSeconds()); }
    @Bean("videoExecutor") ThreadPoolTaskExecutor video(ExecutionProperties p, MeterRegistry m) { return managed("agent-video", p.video(), m,p.shutdownWaitSeconds()); }
    @Bean("sseExecutor") ThreadPoolTaskExecutor sse(ExecutionProperties p, MeterRegistry m) { return managed("agent-sse", p.sse(), m,p.shutdownWaitSeconds()); }
    @Bean("terminationExecutor") ThreadPoolTaskExecutor termination(ExecutionProperties p, MeterRegistry m) { return managed("agent-termination", p.termination(), m,p.shutdownWaitSeconds()); }
    @Bean("memoryExecutor") ThreadPoolTaskExecutor memory(ExecutionProperties p,MeterRegistry m) { return managed("agent-memory",new ExecutionProperties.Pool(p.memoryCore(),p.memoryMax(),p.memoryQueue()),m,p.shutdownWaitSeconds()); }
    @Bean("toolBatchWorkers") ThreadPoolTaskExecutor toolBatch(ExecutionProperties p,MeterRegistry m){return managed("agent-tool-batch",new ExecutionProperties.Pool(p.toolBatchCore(),p.toolBatchMax(),p.toolBatchQueue()),m,p.shutdownWaitSeconds());}
    @Bean("deadlineScheduler") ThreadPoolTaskScheduler scheduler(ExecutionProperties properties) {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(properties.deadlineThreads());
        scheduler.setThreadNamePrefix("agent-deadline-"); scheduler.setRemoveOnCancelPolicy(true); return scheduler;
    }
    /** Default for maintenance annotations; deadlineScheduler is reserved for fast control signals. */
    @Bean(name={"maintenanceScheduler", "taskScheduler"}) ThreadPoolTaskScheduler maintenanceScheduler(ExecutionProperties properties) {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(properties.maintenanceThreads());
        scheduler.setThreadNamePrefix("agent-maintenance-"); scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setDaemon(true); return scheduler;
    }
}
