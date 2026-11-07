package com.tianmu.agent.harness;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.CallControl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PreparationDefaultsTest {
    final AccessScope author=new AccessScope("demo","alice",Set.of(1001L));
    record Outcome(Result result,RunState run,List<io.opentelemetry.sdk.trace.data.SpanData> spans){}
    Outcome prepare(AccessScope owner,String query,String page,Understanding understanding){
        try(var f=new HarnessLifecycleTest.Fixture();var service=new PreparationService((i,c)->Optional.empty(),new PreparationFlowTest.Model(){
            public IntentRecognition recognizeIntent(Input i,CallControl c){return new IntentRecognition(Intent.DANMAKU_REPORT,"audience-report",List.of("video-content","danmaku-analysis","comment-analysis"),i.intentCandidates());}
            public Understanding understand(Input i,CallControl c){return understanding;}
        },new PreparationPolicy(Duration.ofSeconds(1),Duration.ofSeconds(2)),f.harness)){
            ReflectionTestUtils.setField(service,"intentCandidates",new IntentCandidateCatalog(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.tool.ToolCatalog()));
            var run=new RunState(owner,query);if(page!=null)run.pageContext(new RequestContext(page));
            var out=new java.util.concurrent.atomic.AtomicReference<Result>();
            f.harness.execute(run,()->{run.start();var result=service.prepare(run,query);out.set(f.harness.stage(run,"agent.prepare.merge",null,null,Map.of(),()->result));});
            if(out.get().route()==Route.HARNESS){run.prepared(out.get());run.task(new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog()).assemble(run));}
            return new Outcome(out.get(),run,List.copyOf(f.recording.spans));
        }
    }
    Understanding ready(String rewrite){return Understanding.proceed(Map.of(),rewrite);}
    @Test void originalTracePhraseUsesCurrentVideoAndExistingReportDefaultsWithThreeCalls(){
        var out=prepare(author,"生成个视频的报告我看看","1001",ready(null));
        assertEquals(Route.HARNESS,out.result.route());var spec=out.result.request().specification();
        assertEquals("audience.report",spec.type());assertEquals(1001L,spec.conditions().get("video_id"));
        assertEquals(DanmakuTypes.ALL_TIME_START.toString(),spec.conditions().get("start"));
        assertEquals(Set.of(AnalysisContract.Source.VIDEO,AnalysisContract.Source.COMMENT,AnalysisContract.Source.DANMAKU),spec.reading().sources());
        assertEquals(3,out.run.snapshot().preparationCalls());assertTrue(out.run.execution().exchanges().isEmpty());
        assertEquals("unclassified",out.result.request().resolution().ruleTask());
        assertEquals(List.of("video_id","report_template","sources","time_range"),out.result.request().resolution().defaults());
        assertEquals("page_context",out.run.task().specification().provenance().get("video_id"));
        assertEquals("skill_default",out.run.task().specification().provenance().get("start"));
        assertFalse(out.result.request().degradedStages().contains("rewrite"));
        var merge=out.spans.stream().filter(s->s.getName().equals("agent.prepare.merge")).findFirst().orElseThrow();
        assertEquals("CAPABILITY_DEFAULTS",merge.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("preparation.merge_reason")));
    }
    @Test void onlyStructuredMissingFieldsCanBeSatisfiedByDefaults(){
        var missing=new Understanding(true,"要哪个视频、时间和报告类型？",Map.of(),null,ClarificationIssue.MISSING_FIELDS,List.of("video_id","time_range","report_type","sources"));
        assertEquals(Route.HARNESS,prepare(author,"生成个视频的报告我看看","1001",missing).result.route());
        for(var issue:List.of(ClarificationIssue.AMBIGUOUS_TARGET,ClarificationIssue.CONTRADICTORY_CONSTRAINTS,ClarificationIssue.CONFIRMATION_REQUIRED)){
            var out=prepare(author,"生成个视频的报告我看看","1001",new Understanding(true,"需要确认的具体问题",Map.of(),null,issue,List.of()));
            assertEquals(Route.CLARIFY,out.result.route());assertEquals("需要确认的具体问题",out.result.clarification());
            assertEquals(issue.name(),out.result.request().resolution().clarificationReason());
            assertEquals("CLARIFICATION_REQUIRED",out.result.request().resolution().rewriteReason());
        }
        assertEquals(Route.CLARIFY,prepare(author,"生成个视频的报告我看看","1001",Understanding.clarify("旧协议未说明缺什么")).result.route());
    }
    @Test void missingOrAmbiguousTargetDoesNotInventTheFirstOwnedVideo(){
        var missing=prepare(author,"生成个视频的报告我看看",null,ready(null));assertEquals(Route.CLARIFY,missing.result.route());assertTrue(missing.result.clarification().contains("哪个视频"));
        var multiple=prepare(new AccessScope("demo","alice",Set.of(1001L,1002L)),"生成视频1001或视频1002的报告",null,ready(null));
        assertEquals(Route.CLARIFY,multiple.result.route());assertTrue(multiple.result.clarification().contains("选定"));
    }
    @Test void optionalOutputStyleDoesNotBlockAnAlreadyParsedReport(){
        var missing=new Understanding(true,"报告要什么样式？",Map.of(),null,ClarificationIssue.MISSING_FIELDS,List.of("output_style"));
        var out=prepare(author,"生成视频1001的观众反馈报告",null,missing);
        assertEquals(Route.HARNESS,out.result.route());assertEquals("DEFAULTS_SATISFIED",out.result.request().resolution().reason());
        assertTrue(out.result.request().resolution().defaults().contains("report_template"));
    }
    @Test void explicitIncompleteTimeAndUnknownSourceRestrictionsStillRequireClarification(){
        var time=prepare(author,"生成个报告，从2026-10-24 12:00开始","1001",ready(null));assertEquals(Route.CLARIFY,time.result.route());assertTrue(time.result.clarification().contains("开始和结束"));
        var source=prepare(author,"生成个报告，只读取私有笔记","1001",ready(null));assertEquals(Route.CLARIFY,source.result.route());
    }
    @Test void sourceExclusionAndDateSurviveModelSuggestionsAndRewrite(){
        var out=prepare(author,"生成个视频的报告，昨天的，不读取评论","1001",ready("生成个视频的报告，昨天的，读取评论"));
        assertEquals(Route.HARNESS,out.result.route());assertFalse(out.run.task().specification().reading().includes(AnalysisContract.Source.COMMENT));
        assertEquals(out.run.businessDate.minusDays(1).atStartOfDay().toString(),out.run.task().specification().conditions().get("start"));
        assertEquals(out.run.message,out.result.request().query());assertEquals("REJECTED",out.result.request().resolution().rewriteState());
    }
    @Test void publicPageUsesVideoOnlyTemplateAndCannotGainFeedbackAccess(){
        var viewer=new AccessScope("demo","alice",Set.of(1002L),null,Set.of(1001L,1002L));
        var out=prepare(viewer,"生成个视频的报告我看看","1001",ready(null));
        assertEquals(Route.HARNESS,out.result.route());assertEquals("video.content",out.run.task().specification().type());
        assertEquals(Set.of(AnalysisContract.Source.VIDEO),out.run.task().specification().reading().sources());
        assertEquals("FORBIDDEN",assertThrows(AgentException.class,()->prepare(viewer,"生成视频1001的观众反馈报告",null,ready(null))).code());
    }
    @Test void acceptedRewritePreservesOriginalAndFieldProvenance(){
        var out=prepare(author,"生成个视频的报告我看看","1001",ready("为当前视频生成报告"));
        assertEquals(Route.HARNESS,out.result.route());assertEquals("ACCEPTED",out.result.request().resolution().rewriteState());
        assertEquals("为当前视频生成报告",out.run.task().query());assertEquals("生成个视频的报告我看看",out.run.task().originalQuery());
        assertEquals("page_context",out.run.task().specification().provenance().get("video_id"));
    }
    @Test void unsafeAndUnparseableRewriteFallsBackWithoutAskingAgain(){
        for(String rewrite:List.of("生成视频1002的报告","分析全部弹幕和评论生成报告","生成个视频的报告第3秒")){
            var out=prepare(author,"生成个视频的报告我看看","1001",ready(rewrite));
            assertEquals(Route.HARNESS,out.result.route());assertEquals(out.run.message,out.run.task().query());assertEquals("REJECTED",out.result.request().resolution().rewriteState());
        }
    }
}
