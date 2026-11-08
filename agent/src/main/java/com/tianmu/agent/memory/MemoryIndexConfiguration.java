package com.tianmu.agent.memory;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
@EnableConfigurationProperties(MemorySemanticPolicy.class)
public class MemoryIndexConfiguration {
    /** Single disposable-index writer, isolated from durable memory extraction and Dream. */
    @Bean("memoryIndexExecutor") ThreadPoolTaskExecutor memoryIndexExecutor(MemorySemanticPolicy policy){
        var pool=new ThreadPoolTaskExecutor();pool.setThreadNamePrefix("memory-index-");pool.setCorePoolSize(1);pool.setMaxPoolSize(1);pool.setQueueCapacity(1);
        pool.setWaitForTasksToCompleteOnShutdown(true);pool.setAwaitTerminationSeconds(policy.batchTimeoutSeconds()+1);return pool;
    }
}
