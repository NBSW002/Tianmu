package com.tianmu.agent;

import com.tianmu.agent.config.ExecutionProperties;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.runtime.control.LoopPolicy;
import com.tianmu.agent.runtime.control.ToolParallelPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GovernanceConfigurationBaselineTest {
    @TestConfiguration(proxyBeanMethods=false)
    @EnableConfigurationProperties({LoopPolicy.class, ContextPolicy.class, ExecutionProperties.class, ToolParallelPolicy.class})
    static class Binding {}

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Binding.class, com.tianmu.agent.config.GovernanceConfiguration.class);
    }

    @Test void repositoryDefaultsAndDeepseekProfileHaveDistinctExecutionWindows() {
        context().run(context -> {
            assertNull(context.getStartupFailure());
            var loop = context.getBean(LoopPolicy.class);
            assertEquals(Duration.ofSeconds(30), loop.requestTimeout());
            assertEquals(Duration.ofSeconds(5), loop.modelTimeout());
            assertEquals(Duration.ofSeconds(3), loop.finalizeTimeout());
            assertEquals(6, loop.maxTurns());
            assertEquals(16, loop.maxModelCalls());
            assertEquals(64000, loop.maxTokens());
            assertEquals(8192, loop.tokenReservation());
            assertEquals(98304, context.getBean(ContextPolicy.class).maxInputBytes());
            assertEquals(4, context.getBean(ContextPolicy.class).recentMaxMessages());
            assertEquals(8192, context.getBean(ContextPolicy.class).recentMaxBytes());
            assertFalse(context.getBean(ToolParallelPolicy.class).enabled());
            assertEquals(2, context.getBean(ToolParallelPolicy.class).perRun());
            assertEquals(1, context.getBean(ExecutionProperties.class).deadlineThreads());
            assertEquals(2, context.getBean(ExecutionProperties.class).maintenanceThreads());
            assertEquals(new ExecutionProperties.Pool(6, 6, 48), context.getBean(ExecutionProperties.class).preparation());
        });
        context().withPropertyValues("spring.profiles.active=deepseek").run(context -> {
            assertNull(context.getStartupFailure());
            var loop = context.getBean(LoopPolicy.class);
            assertEquals(Duration.ofSeconds(120), loop.requestTimeout());
            assertEquals(Duration.ofSeconds(30), loop.modelTimeout());
            assertEquals(Duration.ofSeconds(15), loop.finalizeTimeout());
            assertEquals(Duration.ofSeconds(5), loop.toolTimeout());
        });
    }

    @Test void environmentAliasesOverrideYamlAndExplicitSpringPropertiesWinOverTheirFallback() {
        var configured = context().withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("governance-environment", Map.of(
                        "AGENT_MAX_MODEL_CALLS", "23", "AGENT_CONTEXT_MAX_INPUT_BYTES", "65536",
                        "AGENT_CONTEXT_RECENT_MAX_MESSAGES", "6", "AGENT_CONTEXT_RECENT_MAX_BYTES", "4096",
                        "AGENT_EXECUTION_DEADLINE_THREADS", "3", "AGENT_EXECUTION_MAINTENANCE_THREADS", "4",
                        "AGENT_TOOLS_PARALLEL_ENABLED", "true", "AGENT_TOOLS_PARALLEL_PER_RUN", "3"))));
        configured.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(23, context.getBean(LoopPolicy.class).maxModelCalls());
            assertEquals(65536, context.getBean(ContextPolicy.class).maxInputBytes());
            assertEquals(6, context.getBean(ContextPolicy.class).recentMaxMessages());
            assertEquals(4096, context.getBean(ContextPolicy.class).recentMaxBytes());
            assertEquals(3, context.getBean(ExecutionProperties.class).deadlineThreads());
            assertEquals(4, context.getBean(ExecutionProperties.class).maintenanceThreads());
            assertEquals(new ToolParallelPolicy(true, 3), context.getBean(ToolParallelPolicy.class));
        });
        configured.withPropertyValues("harness.loop.max-model-calls=19").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(19, context.getBean(LoopPolicy.class).maxModelCalls());
        });
    }

    @Test void invalidBudgetsAndCrossFieldCapacityFailBindingInsteadOfSilentlyClamping() {
        for (String invalid : new String[]{"harness.loop.max-model-calls=0", "harness.loop.model-timeout=0ms",
                "agent.context.max-history-bytes=100000", "agent.execution.runs.core=5",
                "agent.context.recent-max-messages=-1", "agent.context.recent-max-bytes=-1",
                "agent.execution.deadline-threads=0", "agent.execution.maintenance-threads=129",
                "agent.tools.parallel.per-run=0", "agent.tools.parallel.per-run=5"}) {
            context().withPropertyValues(invalid).run(context -> assertNotNull(context.getStartupFailure(), invalid));
        }
    }

    @Test void expandedModulePoliciesBindRealOverridesAndRejectInconsistentRelationships() {
        context().withPropertyValues("agent.compaction.trigger-bytes=6000", "agent.memory.runtime.max-recall-items=7",
                "agent.feedback.runtime.preparation-workers=3", "agent.video.runtime.index-workers=3",
                "agent.storage.runtime.pool-max=9", "harness.trace.delivery.max-batch=41").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(6000, context.getBean(com.tianmu.agent.memory.CompactionProperties.class).triggerBytes());
            assertEquals(7, context.getBean(com.tianmu.agent.memory.MemoryRuntimeProperties.class).maxRecallItems());
            assertEquals(3, context.getBean(com.tianmu.agent.feedback.FeedbackRuntimeProperties.class).preparationWorkers());
            assertEquals(3, context.getBean(com.tianmu.agent.video.VideoRuntimeProperties.class).indexWorkers());
            assertEquals(9, context.getBean(com.tianmu.agent.adapters.storage.StorageRuntimeProperties.class).poolMax());
            assertEquals(41, context.getBean(com.tianmu.agent.harness.trace.TraceDeliveryProperties.class).maxBatch());
        });
        for(String invalid : new String[]{"agent.compaction.max-input-bytes=4096",
                "agent.compaction.output-tokens=65536", "agent.memory.runtime.lease-seconds=1",
                "agent.memory.runtime.extraction-output-tokens=9000", "agent.video.runtime.stale-after-seconds=1",
                "agent.feedback.runtime.preparation-workers=0", "agent.storage.runtime.pool-min=9",
                "harness.trace.delivery.kafka-delivery-millis=1000", "harness.trace.delivery.kafka-ack-millis=4000", "harness.trace.delivery.query-read-millis=4000"}) {
            // max-tokens override makes the otherwise legal compaction output exceed its total budget.
            var configured=context().withPropertyValues(invalid);
            if(invalid.contains("compaction.output"))configured=configured.withPropertyValues("agent.compaction.max-tokens=8192");
            configured.run(context -> assertNotNull(context.getStartupFailure(),invalid));
        }
        context().withPropertyValues("harness.trace.delivery.kafka-request-millis=20000",
                "harness.trace.delivery.kafka-delivery-millis=30000", "harness.trace.delivery.kafka-ack-millis=35000").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(35000,context.getBean(com.tianmu.agent.harness.trace.TraceDeliveryProperties.class).kafkaAckMillis());
        });
    }
}
