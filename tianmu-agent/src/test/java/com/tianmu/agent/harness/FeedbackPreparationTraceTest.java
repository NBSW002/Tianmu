package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.runtime.control.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackPreparationTraceTest {
    @TempDir Path files;
    @Test void rejectedSemanticAttemptIsFailedInTraceWhileItsJobRemainsRetryable(){
        try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();var jobs=new MemoryFeedbackJobStore();var models=mock(FeedbackModels.class);
            when(models.modelName()).thenReturn("fixture");when(models.policyHash()).thenReturn("fixture");
            when(models.analyze(any(),any())).thenThrow(new AgentException("INVALID_ANALYSIS","fixture"));
            FeedbackSnapshotPort source=new FeedbackSnapshotPort(){
                public void authorize(com.tianmu.agent.core.AgentTypes.AccessScope owner,long id){}
                public Receipt export(Request r,com.tianmu.agent.core.AgentTypes.AccessScope owner,CallControl c,java.util.function.Consumer<List<Unit>> sink){
                    sink.accept(List.of(new Unit("C:1",Source.COMMENT,"1","反馈","",false,null,"viewer",Instant.parse("2026-11-01T01:00:00Z"))));return new Receipt(Instant.now(),new Counts(1,0,0,0,1));
                }
            };
            var objects=new FeedbackObjects(new FileObjectStore(files),new ObjectMapper().findAndRegisterModules());var service=new FeedbackAnalysisService(jobs,objects,source,models,FeedbackPolicy.defaults());
            var request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,"feedback-v1");
            var job=service.submit(run.id,run.owner,request,null);service.advance(job.id());assertEquals(Phase.ANALYZING,jobs.get(job.id()).phase());
            assertThrows(AgentException.class,()->f.harness.execute(run,()->f.harness.stage(run,"feedback.work",null,null,Map.of(),()->{
                var failure=service.advance(job.id(),run.cancellation(),()->{});if(failure.isPresent())throw failure.get();return true;
            })));
            var span=f.recording.spans.stream().filter(s->s.getName().equals("feedback.work")).findFirst().orElseThrow();
            assertEquals(StatusCode.ERROR,span.getStatus().getStatusCode());assertEquals("FAILED",span.getAttributes().get(AttributeKey.stringKey("node.status")));
            assertEquals("INVALID_ANALYSIS",span.getAttributes().get(AttributeKey.stringKey("error.code")));assertTrue(MemoryFeedbackJobStore.active(jobs.get(job.id()).phase()));
            assertTrue(jobs.completedLevel(job.id(),0).isEmpty());run.cancel();
        }
    }
    @Test void retryAndTerminalFailureAreErrorsInTraceAndMatchTheDurableLedger(){
        for(String error:List.of("OBJECT_STORE_UNAVAILABLE","ARTIFACT_UNAVAILABLE"))try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();var jobs=new MemoryFeedbackJobStore();var ledger=new MemoryFeedbackPreparationStore();
            FeedbackSnapshotPort source=new FeedbackSnapshotPort(){
                public void authorize(com.tianmu.agent.core.AgentTypes.AccessScope owner,long video){}
                public Receipt export(Request request,com.tianmu.agent.core.AgentTypes.AccessScope owner,CallControl c,java.util.function.Consumer<List<Unit>> sink){throw new AgentException(error,"fixture");}
            };
            var models=mock(FeedbackModels.class);when(models.modelName()).thenReturn("fixture");when(models.policyHash()).thenReturn("fixture");
            var objects=new FeedbackObjects(new FileObjectStore(files),new ObjectMapper().findAndRegisterModules());
            var service=new FeedbackAnalysisService(jobs,objects,source,models,FeedbackPolicy.defaults());
            var prep=new FeedbackPreparationService(service,ledger,objects,FeedbackPolicy.defaults(),new FeedbackClusters(null,null));
            var request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackPreparationService.VERSION);
            var job=prep.request(run.id,run.owner,request,null,Instant.now().plusSeconds(30),Source.COMMENT);
            assertThrows(AgentException.class,()->f.harness.execute(run,()->f.harness.stage(run,"feedback.prepare",null,null,Map.of("analysis.phase","SNAPSHOT"),()->{
                prep.advance(job.id(),FeedbackPreparationStore.Step.SNAPSHOT,run.cancellation());return true;
            })));
            var span=f.recording.spans.stream().filter(s->s.getName().equals("feedback.prepare")).findFirst().orElseThrow();
            assertEquals(StatusCode.ERROR,span.getStatus().getStatusCode());assertEquals("FAILED",span.getAttributes().get(AttributeKey.stringKey("node.status")));
            assertEquals(error,span.getAttributes().get(AttributeKey.stringKey("error.code")));assertEquals("error",span.getAttributes().get(AttributeKey.stringKey("operation.outcome")));
            assertEquals(error.equals("OBJECT_STORE_UNAVAILABLE")?"PENDING":"FAILED",ledger.get(job.id(),FeedbackPreparationStore.Step.SNAPSHOT).state());
            assertEquals(error.equals("ARTIFACT_UNAVAILABLE"),jobs.get(job.id()).phase()==Phase.FAILED);
            verify(models,never()).analyze(any(),any());run.cancel();
        }
    }
}
