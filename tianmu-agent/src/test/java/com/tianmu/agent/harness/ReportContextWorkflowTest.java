package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import com.tianmu.agent.application.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.model.*;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.preparation.DanmakuReportTask;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.ToolCatalog;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ReportContextWorkflowTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void constrainedContextKeepsGoalRecoversOffloadedFactAndCorrectsRejectedClassification(boolean stress) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var requests=new CopyOnWriteArrayList<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));
            byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","fixture"))),
                    "usage",Map.of("prompt_tokens",10,"completion_tokens",1)));
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var repository=new MemoryRunRepository();var owner=new AccessScope("t","u",Set.of(1001L));
            var end=LocalDateTime.now(DanmakuTypes.ZONE).minusSeconds(2).withNano(0);
            var query=new DanmakuTypes.Query(1001,end.minusDays(7),end);
            var groups=new ArrayList<TextGroup>();for(int i=1;i<=120;i++)groups.add(new TextGroup(Integer.toString(i),i==1?"早期关键事实：配送延迟":"需要完整分析的观众问题 "+i,1,500L,false));
            var bins=java.util.stream.IntStream.range(0,12).mapToObj(i->new DanmakuTypes.Bucket(i,i==0?120:0)).toList();
            var data=new DanmakuTypes.Dataset(query,120,1,bins,List.of(),Instant.now(),"fixture",false,
                    new Overview(List.of(),List.of(new Position(0,1000,120)),0,groups,120,120));
            var run=repository.create("分析视频1001的弹幕报告",owner,null,null,null).run();
            var base=DanmakuReportTask.specification(query);
            var spec=new TaskTypes.Specification(base.type(),base.conditions(),base.provenance(),new AnalysisContract(Set.of(AnalysisContract.Source.DANMAKU),true));
            run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.UNKNOWN,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec),null,null));
            var skills=new SkillCatalog();var prompts=new PromptCatalog();run.task(new TaskAssembler(skills,prompts,new ToolCatalog()).assemble(run));
            run.execution().activateSkill("danmaku-analysis");
            var evidence=new BusinessEvidencePolicy().registerDanmakuEvidence(run,data);String original=json.writeValueAsString(data);
            run.execution().observe(new ToolCall("query_danmaku",Map.of("video_id",1001)),
                    new ToolResult("query_danmaku",Map.of("evidence_id",evidence.id(),"classification",DanmakuClassification.empty().view(data.audience())),evidence.id()));
            var tools=HarnessLifecycleTest.dispatcher(skills,(q,s)->{throw new AssertionError("Unexpected metric read");},fixture.harness,repository,repository);
            var properties=new DeepSeekProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-only","fixture","fixture",Duration.ofSeconds(2),384,1536);
            var client=new DeepSeekClient(properties,json,new ContextPolicy(65536,0));
            ModelPort model=new ModelPort() {
                boolean rejected=false,recovered=false;
                String recoveredFact="";
                public Decision decide(ModelContext context){throw new AssertionError("Use the controlled model path");}
                public Decision decide(ModelContext context,CallControl control) {
                    var messages=client.harnessMessages(prompts.text("harness.system")+"\n"+prompts.text("common.current-focus-boundary"),context);
                    client.completeHarness("fixture",messages,context.availableTools(),128,control);
                    var state=context.reportState();assertNotNull(state);assertEquals("danmaku.report",state.taskType());
                    var current=state.feedback().get(0);int offset=current.classifiedGroups();
                    assertEquals(120,current.totalGroups());assertFalse(current.reportStored());
                    String stateMessage=messages.get(messages.size()-1).get("content").toString();
                    assertTrue(stateMessage.startsWith("<task_state>"));assertTrue(stateMessage.contains("\"classifiedGroups\":"+offset));
                    assertTrue(json.valueToTree(messages).toString().contains("confirmed_conditions"));
                    assertEquals(spec,context.task().specification(),"Compression cannot reinterpret source/video/date requirements");
                    if(stress && !rejected) {
                        rejected=true;return Decision.tool("create_danmaku_report",Map.of("evidence_id",current.evidenceId(),"offset",0,"topics_json","{}"));
                    }
                    if(stress && offset==0) {
                        assertEquals("INVALID_REPORT",context.observations().get(context.observations().size()-1).data().get("error_code"));
                        assertTrue(json.valueToTree(messages).toString().contains("INVALID_REPORT"));
                    }
                    if(stress && offset==40 && !recovered) {
                        assertFalse(json.valueToTree(messages).toString().contains("早期关键事实"),"The old source body really left active context");
                        recovered=true;return Decision.tool("read_result",Map.of("result_id",current.evidenceId(),"offset",0,"limit",4096));
                    }
                    if(stress && offset==40) {
                        assertTrue(context.observations().get(context.observations().size()-1).data().toString().contains("早期关键事实：配送延迟"));
                        assertTrue(json.valueToTree(messages).toString().contains("早期关键事实"),"Recovery must enter the model-visible history through a real tool call");
                        recoveredFact="配送延迟";
                    }
                    var topics=new ArrayList<Map<String,Object>>();
                    for(int t=0;t<6;t++) {
                        var ids=new ArrayList<String>();for(int i=t;i<40;i+=6)ids.add("G"+(offset+i+1));
                        topics.add(Map.of("topic_key","T"+(t+1),"title","讨论主题 "+t,"observation",(t==0 && !recoveredFact.isEmpty()?"早期反馈提到"+recoveredFact+"；":"")+"观众关注内容".repeat(90),"recommendation","继续改进".repeat(100),"sample_ids",ids));
                    }
                    try {return Decision.tool("create_danmaku_report",Map.of("evidence_id",current.evidenceId(),"offset",offset,"topics_json",json.writeValueAsString(topics)));}
                    catch(Exception failure){throw new IllegalStateException(failure);}
                }
            };
            new AgentRuntime(model,tools,fixture.harness).execute(run);
            assertEquals(Status.SUCCEEDED,run.snapshot().status(),()->"Failure: "+run.snapshot().errorCode());
            assertEquals(stress?5:3,requests.size());assertEquals(120,run.execution().classification(evidence.id()).offset());
            assertEquals(120,run.execution().classification(evidence.id()).topics().stream().mapToLong(Topic::messages).sum());
            assertTrue(run.execution().hasReport(evidence.id()));assertEquals(original,json.writeValueAsString(run.execution().evidence().get(evidence.id()).analysis()));
            var last=requests.get(requests.size()-1);assertTrue(json.writeValueAsBytes(last).length<65536);
            var withoutProjection=(ObjectNode)last.deepCopy();int restored=0,acceptedInputs=0;
            for(var message:withoutProjection.path("messages"))for(var call:message.path("tool_calls")) {
                var exchange=run.execution().exchanges().get(restored++);
                if(exchange.call().name().equals("create_danmaku_report") && !exchange.result().data().containsKey("error_code")) {
                    assertFalse(json.readTree(call.path("function").path("arguments").asText()).has("topics_json"));acceptedInputs++;
                }
                ((ObjectNode)call.path("function")).put("arguments",json.writeValueAsString(exchange.call().arguments()));
            }
            assertEquals(2,acceptedInputs);assertTrue(json.writeValueAsBytes(withoutProjection).length>65536,"Original large arguments alone exceed the unchanged request limit");
            assertTrue(run.execution().exchanges().stream().filter(e->e.call().name().equals("create_danmaku_report")).allMatch(e->e.call().arguments().containsKey("topics_json")));
            var result=run.execution().observations().get(run.execution().observations().size()-1);
            byte[] report=repository.report(run.id,owner,result.data().get("artifact_id").toString());assertTrue(report.length>0);
            if(stress)assertTrue(new String(report,java.nio.charset.StandardCharsets.UTF_8).contains("早期反馈提到配送延迟"),"The recovered fact must survive through the stored report");
        } finally {server.stop(0);}
    }
}
