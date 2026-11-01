package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import com.tianmu.agent.application.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.model.*;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.preparation.DanmakuReportTask;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class EmptyReportCompletionTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void zeroFeedbackTravelsThroughActualHttpAsAwaitingReportAndDeliveryStillRequiresStorage(boolean skipSave) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            var request=json.readTree(exchange.getRequestBody());var messages=request.path("messages");
            String state=messages.get(messages.size()-1).path("content").asText();
            assertTrue(state.startsWith("<task_state>"));assertTrue(state.contains("\"reportStage\":\"AWAITING_REPORT\""));assertTrue(state.contains("\"emptyFeedback\":true"));
            assertTrue(json.writeValueAsString(messages).contains("classification"));calls.incrementAndGet();
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","fixture"))),"usage",Map.of("prompt_tokens",1,"completion_tokens",1)));
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var repository=new MemoryRunRepository();var owner=new AccessScope("t","u",Set.of(1001L));
            var run=repository.create("只根据视频1001全部弹幕文字生成报告，不分析评论或视频画面",owner,null,null,null).run();
            var end=LocalDateTime.now(DanmakuTypes.ZONE).minusSeconds(1).withNano(0);var query=new DanmakuTypes.Query(1001,DanmakuTypes.ALL_TIME_START,end);
            var base=DanmakuReportTask.specification(query);var spec=new TaskTypes.Specification(base.type(),base.conditions(),base.provenance(),new AnalysisContract(Set.of(AnalysisContract.Source.DANMAKU),true));
            run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.UNKNOWN,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec),null,null));
            var skills=new SkillCatalog();var prompts=new PromptCatalog();run.task(new TaskAssembler(skills,prompts,new ToolCatalog()).assemble(run));run.execution().activateSkill("danmaku-analysis");
            var bins=java.util.stream.IntStream.range(0,12).mapToObj(i->new DanmakuTypes.Bucket(i,0)).toList();
            var data=new DanmakuTypes.Dataset(query,0,0,bins,List.of(),Instant.now(),"fixture",false,new AudienceData.Overview(List.of(),List.of(),0,List.of(),0,0));
            var evidence=new BusinessEvidencePolicy().registerDanmakuEvidence(run,data);
            run.execution().observe(new ToolCall("query_danmaku",Map.of("video_id",1001)),new ToolResult("query_danmaku",Map.of("evidence_id",evidence.id(),"classification",DanmakuClassification.empty().view(data.audience())),evidence.id()));
            var tools=HarnessLifecycleTest.dispatcher(skills,(q,s)->{throw new AssertionError("No metric read");},fixture.harness,repository,repository);
            var client=new DeepSeekClient(new DeepSeekProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-only","fixture","fixture",Duration.ofSeconds(2),384,1536),json);
            ModelPort model=new ModelPort(){
                public Decision decide(ModelContext context){throw new AssertionError("Use controlled path");}
                public Decision decide(ModelContext context,CallControl control){
                    var state=context.reportState().feedback().get(0);assertEquals(ReportTaskState.Stage.AWAITING_REPORT,state.reportStage());assertFalse(state.reportStored());
                    client.completeHarness("fixture",client.harnessMessages(prompts.text("harness.system"),context),context.availableTools(),128,control);
                    return skipSave?Decision.answer("没有弹幕，无法生成报告",List.of(evidence.id())):Decision.tool("create_danmaku_report",Map.of("evidence_id",state.evidenceId()));
                }
            };
            new AgentRuntime(model,tools,fixture.harness).execute(run);assertEquals(1,calls.get());
            if(skipSave){assertEquals("REPORT_REQUIRED",run.snapshot().errorCode());assertFalse(run.execution().hasReport(evidence.id()));assertNull(run.snapshot().answer());}
            else {
                assertEquals(Status.SUCCEEDED,run.snapshot().status(),()->run.snapshot().errorCode());assertTrue(run.execution().hasReport(evidence.id()));
                var result=run.execution().observations().get(run.execution().observations().size()-1);
                byte[] html=repository.report(run.id,owner,result.data().get("artifact_id").toString());assertTrue(html.length>0);
                var doc=json.valueToTree(result.data().get("report_document"));assertEquals(0,doc.path("metrics").path("messages").asInt());assertEquals(0,doc.path("topics").size());
            }
        } finally {server.stop(0);}
    }
}
