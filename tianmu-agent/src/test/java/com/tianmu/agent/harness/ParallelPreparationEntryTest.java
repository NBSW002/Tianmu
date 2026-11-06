package com.tianmu.agent.harness;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.control.CallControl;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelPreparationEntryTest {
    @Test void capabilitySelectionSurvivesMergeAndTaskAssemblyWithThreeCallsOnly() {
        var catalog=new IntentCandidateCatalog(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.tool.ToolCatalog());
        try(var f=new HarnessLifecycleTest.Fixture();var preparation=new PreparationService((i,c)->Optional.empty(),new PreparationFlowTest.Model(){
            @Override public IntentRecognition recognizeIntent(Input input,CallControl control) {
                assertEquals(12,input.intentCandidates().size());
                return new IntentRecognition(Intent.KNOWLEDGE,"knowledge",List.of(),input.intentCandidates());
            }
        },policy(),f.harness)) {
            org.springframework.test.util.ReflectionTestUtils.setField(preparation,"intentCandidates",catalog);
            var run=new RunState(new AccessScope("demo","alice",Set.of(1001L)),"请解释一下视频构图");
            new RequestOrchestrator(preparation,new AgentRuntime(context->{
                assertEquals("knowledge",context.preparation().recognition().primary());
                assertEquals("conversation",context.task().specification().type());return Decision.answer("构图说明",List.of());
            },f.tools,f.harness),f.harness).execute(run);
            assertEquals(Status.SUCCEEDED,run.snapshot().status());assertEquals(3,run.snapshot().preparationCalls());
            for(String name:List.of("agent.prepare.intent","agent.prepare.merge")) {
                var span=f.recording.spans.stream().filter(s->s.getName().equals(name)).findFirst().orElseThrow();
                assertEquals("knowledge",span.getAttributes().get(AttributeKey.stringKey("preparation.intent_primary")));
                assertEquals(12,span.getAttributes().get(AttributeKey.stringArrayKey("preparation.intent_candidates")).size());
            }
        }
    }
    private PreparationPolicy policy() {
        return new Binder(new MapConfigurationPropertySource(Map.of("agent.preparation.faq-timeout","300ms")))
                .bind("agent.preparation",Bindable.of(PreparationPolicy.class)).get();
    }
    @Test void defaultEntryRunsAllThreeBranchesOnTheSameSnapshotBeforeAnyLoopTurn() {
        var policy=policy(); assertFalse(policy.deterministicRouting()); assertTrue(policy.emotionEnabled());
        var barrier=new CyclicBarrier(3); var inputs=new ConcurrentLinkedQueue<Input>();
        try(var f=new HarnessLifecycleTest.Fixture();var preparation=new PreparationService((i,c)->Optional.empty(),new PreparationFlowTest.Model(){
            void meet(Input input){inputs.add(input);try{barrier.await(2,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}}
            public Intent intent(Input i,CallControl c){meet(i);return Intent.VIDEO_METRIC;}
            public Emotion emotion(Input i,CallControl c){meet(i);return Emotion.NEGATIVE;}
            public Understanding understand(Input i,CallControl c){meet(i);return accepted(i,i.query());}
        },policy,f.harness,List.of(new MetricQueryReadiness()))) {
            var run=new RunState(new AccessScope("demo","alice",Set.of(1001L)),"查询视频1001昨天的弹幕数");
            var called=new AtomicInteger();
            new RequestOrchestrator(preparation,new AgentRuntime(context->{
                called.incrementAndGet();assertEquals(3,inputs.size());assertEquals(Emotion.NEGATIVE,context.preparation().emotion());
                assertNotNull(context.task());return Decision.clarify("下一步？");
            },f.tools,f.harness),f.harness).execute(run);
            assertEquals(1,called.get());assertEquals(3,run.snapshot().preparationCalls());assertEquals(Route.HARNESS,run.snapshot().route());
            Input first=inputs.peek();assertTrue(inputs.stream().allMatch(i->i==first));
            var merge=f.recording.spans.stream().filter(s->s.getName().equals("agent.prepare.merge")).findFirst().orElseThrow();
            for(String stage:List.of("intent","emotion","clarification")) {
                var span=f.recording.spans.stream().filter(s->s.getName().equals("agent.prepare."+stage)).findFirst().orElseThrow();
                assertEquals("MODEL",span.getAttributes().get(AttributeKey.stringKey("node.type")));
                assertEquals(merge.getParentSpanId(),span.getParentSpanId());assertTrue(span.getEndEpochNanos()<=merge.getStartEpochNanos());
            }
        }
    }
    @Test void preparationClarificationUsesRegisteredToolHooksAndNeverAssemblesATask() {
        var toolCalls=new AtomicInteger();
        var hook=HarnessLifecycleTest.hook("record-clarification",0,com.tianmu.agent.harness.hook.AgentHook.Mode.OBSERVER,
                Set.of(com.tianmu.agent.harness.hook.HookPoint.PRE_TOOL),event->toolCalls.incrementAndGet());
        try(var f=new HarnessLifecycleTest.Fixture(hook);var preparation=new PreparationService((i,c)->Optional.empty(),new PreparationFlowTest.Model(){
            public Understanding understand(Input i,CallControl c){return Understanding.clarify("你想分析哪个视频？");}
        },policy(),f.harness)) {
            var run=f.run();new RequestOrchestrator(preparation,new AgentRuntime(c->{fail("Loop must not start");return null;},f.tools,f.harness),f.harness).execute(run);
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status());assertEquals(0,run.snapshot().steps());assertNull(run.task());
            assertEquals(1,toolCalls.get());assertEquals(0,f.queries.get());assertTrue(run.execution().activeSkills().isEmpty());
            assertEquals("ask_clarification",run.execution().exchanges().get(0).call().name());
            assertEquals("你想分析哪个视频？",run.snapshot().clarification());
            assertTrue(f.recording.spans.stream().anyMatch(s->s.getName().equals("agent.tool")
                    && "ask_clarification".equals(s.getAttributes().get(AttributeKey.stringKey("tool.name")))));
        }
    }
    @Test void missingConditionsStillRunThreeRecognizersAndHostValidationWins() {
        try(var f=new HarnessLifecycleTest.Fixture();var preparation=new PreparationService((i,c)->Optional.empty(),new PreparationFlowTest.Model(){
            public Intent intent(Input i,CallControl c){return Intent.VIDEO_METRIC;}
            public Understanding understand(Input i,CallControl c){return Understanding.proceed(Map.of(),i.query());}
        },policy(),f.harness,List.of(i->Optional.of(new Clarification(true,"请补充查询日期。"))))) {
            var run=f.run();new RequestOrchestrator(preparation,new AgentRuntime(c->{fail("Loop must not start");return null;},f.tools,f.harness),f.harness).execute(run);
            assertEquals(3,run.snapshot().preparationCalls());assertEquals("请补充查询日期。",run.snapshot().clarification());
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status());assertNull(run.task());
        }
    }
    @Test void transientEmotionProviderFailureDoesNotTurnAValidRequestIntoTaskFailure() {
        try(var f=new HarnessLifecycleTest.Fixture();var preparation=new PreparationService((i,c)->Optional.empty(),new PreparationFlowTest.Model(){
            public Emotion emotion(Input i,CallControl c){throw new com.tianmu.agent.core.AgentException("MODEL_UNAVAILABLE","temporary outage");}
        },policy(),f.harness)) {
            var run=f.run();new RequestOrchestrator(preparation,new AgentRuntime(c->{
                assertEquals(Emotion.UNKNOWN,c.preparation().emotion());return Decision.clarify("下一步？");
            },f.tools,f.harness),f.harness).execute(run);
            assertEquals(Route.HARNESS,run.snapshot().route());assertEquals(Status.NEEDS_INPUT,run.snapshot().status());
            assertTrue(run.preparedRequest().degradedStages().contains("emotion"));assertNull(run.snapshot().errorCode());
        }
    }
}
