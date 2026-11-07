package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.runtime.events.InMemoryRunEventStore;
import com.tianmu.agent.runtime.store.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PendingOperationTest {
    @Test void storedOperationTimestampCannotExtendTheRunsDeadline(){
        var deadline=Instant.parse("2026-11-08T00:00:00.123456400Z");var rounded=Instant.parse("2026-11-08T00:00:00.123457Z");
        assertEquals(deadline,com.tianmu.agent.core.PendingOperation.bounded(UUID.randomUUID(),"feedback-materials",rounded,deadline).deadline());
        assertEquals(deadline.minusSeconds(1),com.tianmu.agent.core.PendingOperation.bounded(UUID.randomUUID(),"feedback-materials",deadline.minusSeconds(1),deadline).deadline());
    }
    @Test void sourceGroupRestoresBothProviderCallsAndRejectsPartialCompletion() throws Exception {
        var r=run();var op=new PendingOperation(UUID.randomUUID(),"feedback-sources",r.deadline());
        for(String name:List.of("analyze_comments","analyze_danmaku"))r.execution().observe(new ToolCall(name,Map.of(),"provider-"+name),new ToolResult(name,Map.of("status","PENDING"),null,null,op));
        r.waitFor(op);
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var frame=json.readValue(json.writeValueAsBytes(r.continuation()),PendingOperation.Continuation.class);
        var restored=new RunState(r.id,r.sessionId,r.turnSeq,r.owner,r.message,r.query(),r.createdAt,r.deadline(),RunBackend.MEMORY,new InMemoryRunEventStore(),new InMemoryEvidenceStore(),true);
        restored.restore(r.snapshot());restored.restoreContinuation(frame);
        var comments=new ToolResult("analyze_comments",Map.of("status","READY"),"ev_comments");
        var danmaku=new ToolResult("analyze_danmaku",Map.of("status","READY"),"ev_danmaku");
        assertThrows(IllegalArgumentException.class,()->restored.completeOperations(op,List.of(comments)));
        assertEquals(Status.WAITING_OPERATION,restored.snapshot().status());
        assertTrue(restored.completeOperations(op,List.of(danmaku,comments)));
        assertEquals("provider-analyze_comments",restored.execution().exchanges().get(0).call().providerCallId());
        assertEquals(comments,restored.execution().exchanges().get(0).result());assertEquals(danmaku,restored.execution().exchanges().get(1).result());
        r.cancel();restored.cancel();
    }
    @Test void harnessResumptionKeepsTheOriginalTraceInsteadOfRejectingANewTraceId(){
        try(var provider=io.opentelemetry.sdk.trace.SdkTracerProvider.builder().build()){
            var tracer=provider.get("test");var hooks=new com.tianmu.agent.harness.hook.HookDispatcher(List.of(),tracer);
            try(var first=new com.tianmu.agent.harness.RuntimeHarness(tracer,hooks);var second=new com.tianmu.agent.harness.RuntimeHarness(tracer,hooks)){
                var r=run();first.execute(r,()->waitOn(r));var frame=r.continuation();assertNotNull(frame.traceParent());
                var restored=new RunState(r.id,r.sessionId,r.turnSeq,r.owner,r.message,r.query(),r.createdAt,r.deadline(),RunBackend.MEMORY,new InMemoryRunEventStore(),new InMemoryEvidenceStore(),true);
                restored.restore(r.snapshot());restored.restoreContinuation(frame);
                second.start(restored);assertEquals(r.snapshot().traceId(),restored.snapshot().traceId());restored.cancel();r.cancel();
            }
        }
    }
    @Test void failedCompletionWriteRestoresPendingPairForRetry(){
        var fail=new java.util.concurrent.atomic.AtomicBoolean();
        var r=new RunState(UUID.randomUUID(),UUID.randomUUID(),1,new AccessScope("t","u",Set.of(1001L)),"x","x",Instant.now(),Instant.now().plusSeconds(30),new RunBackend(){
            public void save(RunState run){if(fail.get() && run.snapshot().status()==Status.RUNNING)throw new AgentException("STORAGE_UNAVAILABLE","Injected completion failure");}
        },new InMemoryRunEventStore(),new InMemoryEvidenceStore(),false);r.start();var pending=waitOn(r);fail.set(true);
        var result=new ToolResult("analyze_feedback",Map.of("status","READY"),"ev_test");assertThrows(AgentException.class,()->r.completeOperation(pending,result));
        assertEquals(Status.WAITING_OPERATION,r.snapshot().status());assertEquals(pending,r.execution().observations().get(0).pending());
        fail.set(false);assertTrue(r.completeOperation(pending,result));r.cancel();
    }
    RunState run(){var r=new RunState(new AccessScope("t","u",Set.of(1001L)),"反馈分析");r.start();return r;}
    PendingOperation waitOn(RunState run){
        var op=new PendingOperation(UUID.randomUUID(),"feedback-analysis",run.deadline());
        run.execution().activateSkill("audience-report");run.execution().observe(new ToolCall("analyze_feedback",Map.of(),"provider-call"),new ToolResult("analyze_feedback",Map.of("status","PENDING"),null,null,op));run.waitFor(op);return op;
    }
    @Test void continuationRestoresPairingSkillAndTokenSpendingWithoutReusingAPendingReply(){
        var r=run();var pending=waitOn(r);var snapshot=r.snapshot();var frame=r.continuation();
        var restored=new RunState(r.id,r.sessionId,r.turnSeq,r.owner,r.message,r.query(),r.createdAt,r.deadline(),RunBackend.MEMORY,new InMemoryRunEventStore(),new InMemoryEvidenceStore(),true);
        restored.restore(snapshot);restored.restoreContinuation(frame);
        assertFalse(AgentTypes.terminal(restored.snapshot().status()));assertEquals(Set.of("audience-report"),restored.execution().activeSkills());
        assertEquals(frame.budget(),restored.modelBudget().snapshot());
        var result=new ToolResult("analyze_feedback",Map.of("status","READY"),"ev_1");assertTrue(restored.completeOperation(pending,result));
        assertEquals(Status.RUNNING,restored.snapshot().status());assertNull(restored.snapshot().operation());assertNull(restored.continuation());
        assertEquals("provider-call",restored.execution().exchanges().get(0).call().providerCallId());assertEquals(result,restored.execution().observations().get(0));
        assertFalse(restored.completeOperation(pending,result));r.cancel();restored.cancel();
    }
    @Test void cancellationWinsOverLateCompletionAndPendingCannotPretendToBeDelivery(){
        var r=run();var pending=waitOn(r);r.cancel();
        assertThrows(AgentException.class,()->r.completeOperation(pending,new ToolResult("analyze_feedback",Map.of(),"ev_1")));
        assertEquals(Status.CANCELLED,r.snapshot().status());
        assertThrows(IllegalArgumentException.class,()->new ToolResult("analyze_feedback",Map.of(),"ev_1",new Answer("done",List.of("ev_1")),pending));
    }
    @Test void aFailedWaitWriteDoesNotExposeAFalseWaitingState(){
        var r=new RunState(UUID.randomUUID(),UUID.randomUUID(),1,new AccessScope("t","u",Set.of(1001L)),"x","x",Instant.now(),Instant.now().plusSeconds(30),new RunBackend(){
            public void save(RunState run){if(run.snapshot().status()==Status.WAITING_OPERATION)throw new AgentException("STORAGE_UNAVAILABLE","Injected write failure");}
        },new InMemoryRunEventStore(),new InMemoryEvidenceStore(),false);r.start();
        assertThrows(AgentException.class,()->waitOn(r));assertEquals(Status.RUNNING,r.snapshot().status());assertNull(r.snapshot().operation());r.cancel();
    }
}
