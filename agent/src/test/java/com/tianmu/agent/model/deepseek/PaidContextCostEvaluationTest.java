package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.AgentApplication;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.model.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.store.*;
import com.tianmu.agent.runtime.control.CallControl;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in, real provider, synthetic feedback only. Three bounded runs and one bounded feedback job. */
@EnabledIfEnvironmentVariable(named="AGENT_PAID_EVAL",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("demo")
@SpringBootTest(classes={AgentApplication.class,PaidContextCostEvaluationTest.RealModels.class},properties={
        "harness.loop.request-timeout=480s","harness.loop.model-timeout=60s","harness.loop.tool-timeout=30s","harness.loop.finalize-timeout=60s",
        "harness.loop.max-model-calls=8","harness.loop.max-tokens=128000","agent.feedback.max-model-calls=8","agent.feedback.max-tokens=120000",
        "agent.feedback.model-timeout=60s","agent.feedback.max-records=243","agent.qa.enabled=false","harness.trace.details.enabled=true"})
class PaidContextCostEvaluationTest {
    static Path output(){return Path.of(Objects.requireNonNull(System.getenv("PAID_EVAL_DIR"),"PAID_EVAL_DIR required")).toAbsolutePath();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("harness.trace.directory",()->output().resolve("trace").toString());}
    @TestConfiguration(proxyBeanMethods=false) static class RealModels {
        @Bean DeepSeekProperties evaluationProperties(){return new DeepSeekProperties(URI.create("https://api.deepseek.com"),System.getenv("DEEPSEEK_API_KEY"),
                "deepseek-flash","deepseek-flash",Duration.ofSeconds(10),384,3072,"deepseek-flash");}
        @Bean DeepSeekClient evaluationClient(DeepSeekProperties properties,ObjectMapper json,Tracer tracer){return new DeepSeekClient(properties,json,ContextPolicy.defaults(),tracer);}
        @Bean @Primary ModelPort evaluationLoop(DeepSeekClient client,DeepSeekProperties properties,ObjectMapper json,PromptCatalog prompts){return new DeepSeekHarnessModel(client,properties,json,prompts);}
        @Bean @Primary FeedbackModels evaluationAnalysis(DeepSeekClient client,DeepSeekProperties properties,ObjectMapper json,PromptCatalog prompts){return new DeepSeekFeedbackModels(client,properties,json,prompts);}
        @Bean @Primary ObjectStore evaluationObjects(){return new com.tianmu.agent.adapters.storage.FileObjectStore(output().resolve("objects"));}
        @Bean @Primary com.tianmu.agent.query.VideoSearchPort evaluationSearch(){return (query,owner,control)->{
            control.checkpoint();
            var date=Instant.now().minus(Duration.ofDays(7));
            return new com.tianmu.agent.query.VideoSearchPort.Page(List.of(
                    new com.tianmu.agent.query.VideoSearchPort.Candidate("1001","小鸭视频",31000,date),
                    new com.tianmu.agent.query.VideoSearchPort.Candidate("1003","小鸭视频",31000,date)),2,false);
        };}
        @Bean @Primary FeedbackSnapshotPort evaluationFeedback(){return new FeedbackSnapshotPort(){
            public void authorize(AccessScope owner,long video){if(!owner.videoIds().contains(video))throw new AgentException("FORBIDDEN","Video outside fixture");}
            public Receipt export(Request request,AccessScope owner,CallControl control,java.util.function.Consumer<List<Unit>> sink){
                authorize(owner,request.query().videoId());control.checkpoint();assertEquals(Set.of(Source.COMMENT),request.sources());
                String[] topics={"字幕太小，请增大字号", "字幕现在刚好，增大会遮挡画面", "希望补充关键步骤的慢动作演示", "背景音乐偏大，讲解不容易听清", "步骤讲得很清楚，希望继续这个系列", "想了解器材清单和预算"};
                var rows=new ArrayList<Unit>();
                for(int i=1;i<=243;i++)rows.add(new Unit("C:"+i,Source.COMMENT,""+i,"【合成验收数据】"+topics[(i-1)%topics.length]+"，反馈编号"+i,"",false,null,"fixture-"+i,request.query().end().minusSeconds(1).atZone(DanmakuTypes.ZONE).toInstant()));
                sink.accept(rows);return new Receipt(Instant.now(),new Counts(243,0,0,0,243));
            }
        };}
    }
    @Autowired RunService runs;@Autowired FeedbackAnalysisService feedback;@Autowired ObjectMapper json;@Autowired ReportStore reports;@Autowired SdkTracerProvider tracer;
    final AccessScope owner=new AccessScope("demo","alice",Set.of(1001L,1003L));
    final List<RunState> executed=new ArrayList<>();final List<Map<String,Object>> cases=new ArrayList<>();
    RunState run(String name,String query,UUID session)throws Exception{
        var run=runs.create(query,owner,session,null,null);executed.add(run);
        var terminal=new java.util.concurrent.CountDownLatch(1);run.onTerminal(s->terminal.countDown());
        assertTrue(terminal.await(490,TimeUnit.SECONDS),"Bounded real run did not converge");
        var row=new LinkedHashMap<String,Object>();row.put("case",name);row.put("run_id",run.id.toString());row.put("status",run.snapshot().status());
        row.put("error_code",run.snapshot().errorCode());row.put("loop_calls",run.snapshot().modelCalls());
        var job=feedback.forRun(run.id);if(job!=null){row.put("feedback_calls",job.modelCalls());row.put("feedback_charged_tokens",job.chargedTokens());}
        cases.add(row);save();return run;
    }
    void save()throws Exception{Files.createDirectories(output());json.writerWithDefaultPrettyPrinter().writeValue(output().resolve("cases.json").toFile(),Map.of(
            "data_scope","243_SYNTHETIC_COMMENTS_NO_PRODUCTION_DATA","token_budget_upper_bound",504000,"cases",cases));}
    void finishEvaluation()throws Exception{
        executed.stream().filter(r->!AgentTypes.terminal(r.snapshot().status())).forEach(RunState::cancel);
        tracer.forceFlush().join(10,TimeUnit.SECONDS);save();
    }
    @Test void reportAndHistoricalReadThroughRealLoop()throws Exception{
        try{
            var report=run("generate_report","为视频1001生成昨天的评论报告，不读取视频内容",null);
            assertEquals(Status.SUCCEEDED,report.snapshot().status(),report.snapshot().errorCode());
            var stored=report.execution().observations().stream().filter(r->r.tool().equals("create_feedback_report")).findFirst().orElseThrow();
            String artifact=stored.data().get("artifact_id").toString();
            var evidence=report.execution().evidence().snapshot().values().stream().filter(e->e.feedback()!=null).findFirst().orElseThrow();
            assertEquals(243,evidence.feedback().counts().total());
            byte[] html=reports.report(report.id,owner,artifact);Files.write(output().resolve("synthetic-report.html"),html);
            String rendered=new String(html,java.nio.charset.StandardCharsets.UTF_8);
            for(String theme:List.of("字幕","遮挡","慢动作","音乐","清楚","器材"))assertTrue(rendered.contains(theme),"Missing fixture viewpoint: "+theme);
            var old=run("read_history","读取这份历史报告并概括主要观点，不重新分析：/api/agent/runs/"+report.id+"/reports/"+artifact,report.sessionId);
            assertEquals(Status.SUCCEEDED,old.snapshot().status(),old.snapshot().errorCode());assertNull(feedback.forRun(old.id));
            assertTrue(old.execution().observations().stream().anyMatch(r->r.tool().equals("read_report")));
        }finally{finishEvaluation();}
    }
    @Test void resourceAmbiguityThroughRealLoop()throws Exception{
        try{
            var ambiguous=run("resource_ambiguity","分析我上周那个小鸭视频的评论",null);
            assertEquals(Status.NEEDS_INPUT,ambiguous.snapshot().status());assertNull(feedback.forRun(ambiguous.id));
            assertTrue(ambiguous.execution().observations().stream().anyMatch(r->r.tool().equals("search_my_videos")));
            assertTrue(ambiguous.execution().observations().stream().noneMatch(r->r.tool().equals("analyze_feedback")));
        }finally{finishEvaluation();}
    }
}
