package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.runtime.RunState;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundTraceAccountingTest {
    private SpanData finish(HarnessLifecycleTest.Fixture f) {
        return f.recording.spans.stream().filter(s->s.getName().equals("finish")).findFirst().orElseThrow();
    }
    @Test void fourForegroundAndFourBackgroundCallsProduceEightCompleteCalls() {
        try(var f=new HarnessLifecycleTest.Fixture()) {
            var run=f.run();run.start();
            f.harness.execute(run,()->{
                for(int i=0;i<4;i++) {
                    run.modelCallStarted();
                    f.harness.stage(run,"agent.model",null,null,Map.of(),()->{TraceUsage.record("flash",100,10);return true;});
                }
                for(int i=1;i<=4;i++) {
                    run.backgroundModelCalls(i);run.backgroundModelCalls(i);
                    f.harness.stage(run,"feedback.work",null,null,Map.of("analysis.job_id","job","analysis.phase","CLASSIFYING"),()->
                        TraceModelCall.call("feedback.model",Map.of("analysis.work_id","work","analysis.batch_ordinal",0),()->{
                            TraceUsage.externalCall();TraceUsage.record("flash",200,20);return List.of("classified");
                        }));
                }
                run.clarify("complete");
            });
            assertEquals(8,run.snapshot().totalModelCalls());
            assertEquals(4,run.snapshot().backgroundModelCalls());
            var end=finish(f).getAttributes();
            assertEquals(8L,end.get(AttributeKey.longKey("usage.reported_calls")));
            assertEquals(1320L,end.get(AttributeKey.longKey("usage.total_tokens")));
            assertEquals(true,end.get(AttributeKey.booleanKey("usage.complete")));
            assertEquals("REPORTED",end.get(AttributeKey.stringKey("usage.state")));
            assertEquals("FOREGROUND",end.get(AttributeKey.stringKey("budget.scope")));
            var children=f.recording.spans.stream().filter(s->s.getName().equals("feedback.model")).toList();
            assertEquals(4,children.size());
            for(var child:children) {
                assertEquals("MODEL",child.getAttributes().get(AttributeKey.stringKey("node.type")));
                assertEquals(run.id.toString(),child.getAttributes().get(AttributeKey.stringKey("run.id")));
                assertEquals("alice",child.getAttributes().get(AttributeKey.stringKey("actor.id")));
                assertTrue(f.recording.spans.stream().anyMatch(p->p.getName().equals("feedback.work") && p.getSpanId().equals(child.getParentSpanId())));
            }
            assertTrue(f.recording.spans.stream().filter(s->s.getName().equals("feedback.work"))
                .allMatch(s->"CLASSIFYING".equals(s.getAttributes().get(AttributeKey.stringKey("analysis.phase")))));
        }
    }
    @Test void failedUsageCannotBeHiddenByMatchingSuccessfulCount() {
        try(var f=new HarnessLifecycleTest.Fixture()) {
            var run=f.run();run.start();
            f.harness.execute(run,()->{
                run.modelCallStarted();TraceUsage.record("flash",100,10);
                TraceUsage.record("flash",-1,-1);
                run.clarify("done");
            });
            assertEquals(false,finish(f).getAttributes().get(AttributeKey.booleanKey("usage.complete")));
            assertEquals("PARTIAL",finish(f).getAttributes().get(AttributeKey.stringKey("usage.state")));
        }
    }
    @Test void cumulativeBackgroundLedgerSurvivesJsonRestoreWithoutConsumingForegroundLimit() throws Exception {
        var owner=new AccessScope("t","u",Set.of(1L));
        var run=new RunState(owner,"test");run.bindControl(Duration.ofSeconds(30),2);
        run.modelCallStarted();run.backgroundModelCalls(100);
        var json=new ObjectMapper().findAndRegisterModules();
        var saved=json.readValue(json.writeValueAsBytes(run.snapshot()),Snapshot.class);
        var restored=new RunState(owner,"test");restored.bindControl(Duration.ofSeconds(30),2);restored.restore(saved);
        restored.backgroundModelCalls(100);restored.backgroundModelCalls(99);
        restored.modelCallStarted();
        assertEquals(102,restored.snapshot().totalModelCalls());
        assertEquals(100,restored.snapshot().backgroundModelCalls());
        assertThrows(com.tianmu.agent.core.AgentException.class,restored::modelCallStarted);
        var legacy=json.valueToTree(run.snapshot());((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove("backgroundModelCalls");
        assertEquals(0,json.treeToValue(legacy,Snapshot.class).backgroundModelCalls());
    }
    @Test void resumedProcessLabelsOnlyItsOwnUsageAsASegment() {
        try(var f=new HarnessLifecycleTest.Fixture()) {
            var run=f.run();run.start();run.modelCallStarted();run.backgroundModelCalls(3);
            f.harness.execute(run,()->{run.modelCallStarted();TraceUsage.record("flash",10,2);run.clarify("done");});
            assertEquals("RUN_PROCESS_SEGMENT",finish(f).getAttributes().get(AttributeKey.stringKey("usage.scope")));
            assertEquals(true,finish(f).getAttributes().get(AttributeKey.booleanKey("usage.complete")));
            assertEquals(5,run.snapshot().totalModelCalls());
        }
    }
}
