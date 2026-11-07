package com.tianmu.agent.harness;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.config.ExecutionConfiguration;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeToolBatchTest {
    ToolCall call(int days){return new ToolCall("query_video_metrics",Map.of("video_id",1001,"date",LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(days).toString(),"metric","bullet_count"),"call-"+days,"batch-1");}
    Decision mixed(int n) {
        String group="g-"+n;
        return new Decision(Kind.TOOL,new ToolCall("analyze_video_content",Map.of("video_id",1001),"video-"+n,group,"text","reasoning"),null,null,
                List.of(new ToolCall("analyze_comments",Map.of(),"comments-"+n,group,"text","reasoning")));
    }
    @Test void mixedSuspensionBatchReturnsPairedFeedbackBeforeAnyHandlerAndAllowsReplan() {
        try(var f=new HarnessLifecycleTest.Fixture()) {
            var run=f.run();run.execution().activateSkill("audience-report");var turns=new AtomicInteger();
            f.execute(run,context->{
                if(turns.getAndIncrement()==0)return mixed(1);
                assertEquals(2,context.exchanges().size());
                assertTrue(f.recording.spans.stream().noneMatch(s->s.getName().equals("agent.tool")));
                assertEquals(List.of("video-1","comments-1"),context.exchanges().stream().map(e->e.call().providerCallId()).toList());
                assertTrue(context.exchanges().stream().allMatch(e->e.result().data().get("error_code").equals("INVALID_TOOL_BATCH") && e.result().evidenceId()==null && e.result().pending()==null));
                var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
                var client=new com.tianmu.agent.model.deepseek.DeepSeekClient(new com.tianmu.agent.model.deepseek.DeepSeekProperties(java.net.URI.create("http://localhost"),"test","fixture","fixture",Duration.ofSeconds(2),384,1536),json);
                var messages=json.valueToTree(client.harnessMessages("rules",context));
                var assistant=messages.get(messages.size()-3);
                assertEquals("assistant",assistant.path("role").asText());assertEquals("reasoning",assistant.path("reasoning_content").asText());
                assertEquals(2,assistant.path("tool_calls").size());
                assertEquals("video-1",messages.get(messages.size()-2).path("tool_call_id").asText());
                assertEquals("comments-1",messages.get(messages.size()-1).path("tool_call_id").asText());
                return Decision.clarify("请补充重点");
            });
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status(),()->String.valueOf(run.snapshot().errorCode()));
            assertEquals(0,f.queries.get());assertEquals(2,turns.get());
            var batch=f.recording.spans.stream().filter(s->s.getName().equals("agent.tool.batch")).findFirst().orElseThrow();
            var attrs=com.tianmu.agent.harness.trace.TraceEnvelope.from(batch,"test","v1").attributes();
            assertEquals(true,attrs.get("tool.batch_rejected"));assertEquals(false,attrs.get("tool.handler_executed"));assertEquals("INVALID_TOOL_BATCH",attrs.get("tool.error_code"));
        }
    }
    @Test void repeatedMixedBatchesStopAtSharedCorrectionLimit() {
        try(var f=new HarnessLifecycleTest.Fixture()) {
            var run=f.run();run.execution().activateSkill("audience-report");var turns=new AtomicInteger();
            f.execute(run,context->mixed(turns.incrementAndGet()));
            assertEquals("TOOL_FAILURE_LIMIT",run.snapshot().errorCode());assertEquals(3,turns.get());
            assertEquals(4,run.execution().exchanges().size());assertEquals(0,f.queries.get());
        }
    }
    @Test void serializedContinuationKeepsBatchCorrectionLimit() throws Exception {
        try(var f=new HarnessLifecycleTest.Fixture()) {
            var original=f.run();original.execution().activateSkill("audience-report");var turns=new AtomicInteger();
            f.execute(original,context->mixed(turns.incrementAndGet()));
            var pending=new com.tianmu.agent.core.PendingOperation(UUID.randomUUID(),"example-export",Instant.now().plusSeconds(60));
            var saved=new com.tianmu.agent.core.PendingOperation.Continuation(pending,original.execution().activeSkills(),original.execution().exchanges(),null,null);
            var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            var restored=json.readValue(json.writeValueAsBytes(saved),com.tianmu.agent.core.PendingOperation.Continuation.class);
            var run=f.run();run.execution().restore(restored);var calls=new AtomicInteger();
            f.execute(run,context->mixed(calls.incrementAndGet()));
            assertEquals("TOOL_FAILURE_LIMIT",run.snapshot().errorCode());assertEquals(1,calls.get());assertEquals(4,run.execution().exchanges().size());
        }
    }
    @Test void mixedBatchDoesNotInvokeEitherHandler() {
        try(var f=new HarnessLifecycleTest.Fixture()) {
            var handlers=new CatalogArchitectureTest().builtins();
            var video=org.mockito.Mockito.spy(new com.tianmu.agent.tool.builtin.AnalyzeVideoContentTool(null,new com.fasterxml.jackson.databind.ObjectMapper()));
            var comments=org.mockito.Mockito.spy(new com.tianmu.agent.tool.builtin.AnalyzeCommentsTool());
            handlers.removeIf(t->t.name().equals(video.name()) || t.name().equals(comments.name()));handlers.add(video);handlers.add(comments);
            var skills=new SkillCatalog();var registry=new com.tianmu.agent.tool.ToolRegistry(handlers,new com.tianmu.agent.tool.ToolCatalog(),skills);
            var tools=new com.tianmu.agent.tool.ToolDispatcher(skills,f.harness,registry);var run=f.run();run.execution().activateSkill("audience-report");var calls=new AtomicInteger();
            com.tianmu.agent.application.AgentRuntimeFactory.create(ctx->calls.getAndIncrement()==0?mixed(1):Decision.clarify("请补充"),tools,f.harness).execute(run);
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status());
            org.mockito.Mockito.verify(video,org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(comments,org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        }
    }
    @Test void transformedFinalBatchIsReadmittedBeforeAnyHandlerStarts(){
        var transform=new com.tianmu.agent.harness.hook.TransformHook<ToolCall>(){
            public String name(){return "change-resource";}
            public com.tianmu.agent.harness.hook.TransformPoint<ToolCall> point(){return com.tianmu.agent.harness.hook.TransformPoint.TOOL_CALL;}
            public ToolCall transform(ToolCall call,com.tianmu.agent.harness.hook.TransformContext context){
                var args=new HashMap<>(call.arguments());args.put("video_id",1002);
                return new ToolCall(call.name(),args,call.providerCallId(),call.providerGroupId());
            }
        };
        try(var f=new HarnessLifecycleTest.Fixture(List.of(transform))){
            var run=f.run();run.execution().activateSkill("video-metrics");
            f.execute(run,ctx->new Decision(Kind.TOOL,call(1),null,null,List.of(call(2))));
            assertEquals("FORBIDDEN",run.snapshot().errorCode());assertEquals(0,f.queries.get());assertTrue(run.execution().exchanges().isEmpty());
        }
    }
    @Test void independentQueriesReallyOverlapButEvidenceAndMessagesCommitInOriginalOrder()throws Exception{
        var workers=ExecutionConfiguration.standalone("batch-test",2,2,4);
        try(var f=new HarnessLifecycleTest.Fixture()){
            var both=new CountDownLatch(2);var finished=new CopyOnWriteArrayList<String>();
            var tools=HarnessLifecycleTest.dispatcher(new SkillCatalog(),(q,s)->{
                both.countDown();try{assertTrue(both.await(3,TimeUnit.SECONDS));if(q.date().equals(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1)))Thread.sleep(100);}catch(InterruptedException e){throw new RuntimeException(e);}
                finished.add(q.date().toString());return new MetricData(q.videoId(),q.date(),q.metric(),42,"v1","fixture",Instant.now(),"Asia/Shanghai",true,true);
            },f.harness);
            var run=f.run();run.start();run.execution().activateSkill("video-metrics");var calls=List.of(call(1),call(2));tools.checkBatch(calls,run);
            var executor=new ToolBatchExecutor(f.harness,workers,true,2);assertTrue(executor.accepts(calls,run,tools));
            var results=executor.execute(run,calls,tools);
            assertEquals(calls.get(1).arguments().get("date"),finished.get(0));
            assertTrue(run.execution().evidence().snapshot().isEmpty());assertTrue(run.execution().exchanges().isEmpty());
            for(var result:results){result.branch().commit(result.result());run.execution().observe(result.call(),result.result());}
            assertEquals(calls,run.execution().exchanges().stream().map(e->e.call()).toList());
            assertEquals(2,run.execution().evidence().snapshot().size());
            assertEquals(List.of("call-1","call-2"),results.stream().map(r->r.call().providerCallId()).toList());
        }finally{workers.shutdown();}
    }
    @Test void cancellationStopsReplenishmentAndDiscardsLateEvidence()throws Exception{
        var workers=ExecutionConfiguration.standalone("batch-cancel",2,2,4);var coordinator=Executors.newSingleThreadExecutor();
        try(var f=new HarnessLifecycleTest.Fixture()){
            var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var count=new AtomicInteger();
            var tools=HarnessLifecycleTest.dispatcher(new SkillCatalog(),(q,s)->{
                count.incrementAndGet();entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
                return new MetricData(q.videoId(),q.date(),q.metric(),1,"v1","fixture",Instant.now(),"Asia/Shanghai",true,true);
            },f.harness);
            var run=f.run();run.start();run.execution().activateSkill("video-metrics");var batch=new ToolBatchExecutor(f.harness,workers,true,2);
            var future=coordinator.submit(()->batch.execute(run,List.of(call(1),call(2),call(3)),tools));
            assertTrue(entered.await(3,TimeUnit.SECONDS));run.cancel();release.countDown();
            assertThrows(ExecutionException.class,()->future.get(4,TimeUnit.SECONDS));assertEquals(2,count.get());
            assertTrue(run.execution().evidence().snapshot().isEmpty());assertTrue(run.execution().exchanges().isEmpty());
        }finally{coordinator.shutdownNow();workers.shutdown();}
    }
    @Test void unsafeMixedBatchFallsBackAndBranchRejectsForgedDelivery(){
        var workers=ExecutionConfiguration.standalone("batch-mode",2,2,4);
        try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();run.start();run.execution().activateSkill("video-metrics");
            var executor=new ToolBatchExecutor(f.harness,workers,true,2);
            assertFalse(executor.accepts(List.of(call(1),new ToolCall("read_skill",Map.of("name","video-metrics"))),run,f.tools));
            var branch=f.tools.branch(call(1),run);
            assertEquals("INVALID_TOOL_RESULT",assertThrows(AgentException.class,()->branch.validate(new ToolResult("read_skill",Map.of(),null))).code());
            assertEquals("INVALID_EVIDENCE",assertThrows(AgentException.class,()->branch.validate(new ToolResult("query_video_metrics",Map.of(),"forged"))).code());
        }finally{workers.shutdown();}
    }
    @Test void recoverableBranchFailureIsARealFailedTraceNodeAndKeepsItsSiblingResult(){
        var workers=ExecutionConfiguration.standalone("batch-error",2,2,4);
        try(var f=new HarnessLifecycleTest.Fixture()){
            var tools=HarnessLifecycleTest.dispatcher(new SkillCatalog(),(q,s)->{
                if(q.date().equals(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1)))throw new AgentException("VIDEO_MODEL_TIMEOUT","fixture timeout");
                return new MetricData(q.videoId(),q.date(),q.metric(),42,"v1","fixture",Instant.now(),"Asia/Shanghai",true,true);
            },f.harness);
            var run=f.run();run.start();run.execution().activateSkill("video-metrics");
            var results=new ToolBatchExecutor(f.harness,workers,true,2).execute(run,List.of(call(1),call(2)),tools);
            assertFalse(results.get(0).success());assertTrue(results.get(1).success());
            var failed=f.recording.spans.stream().filter(s->s.getName().equals("agent.tool") && Boolean.FALSE.equals(s.getAttributes().get(io.opentelemetry.api.common.AttributeKey.booleanKey("tool.success")))).findFirst().orElseThrow();
            assertEquals("FAILED",failed.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("node.status")));
            assertEquals("business_error",failed.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("operation.outcome")));
            assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,failed.getStatus().getStatusCode());
        }finally{workers.shutdown();}
    }
}
