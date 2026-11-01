package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import com.tianmu.agent.application.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.tool.builtin.CreateDanmakuReportTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisAdmissionTest {
    void prepare(RunState run) {
        run.start();
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.UNKNOWN,Set.of()),null,null));
        run.task(new TaskAssembler(new SkillCatalog(),new PromptCatalog(),new ToolCatalog()).assemble(run));
    }
    VideoContent.Facts video() {
        return new VideoContent.Facts("video-content-v1",1001,"fixture",0,1000,"<鸭子>",
                List.of(new VideoContent.Moment(500,"鸭子经过")),"","SAMPLED_FRAMES",List.of(500L),"fixture",Instant.now());
    }
    void prepareFeedbackReport(RunState run,boolean audience,boolean withVideo) {
        run.start();var end=LocalDateTime.now(DanmakuTypes.ZONE).minusSeconds(2).withNano(0);
        var selected=EnumSet.of(audience?AnalysisContract.Source.COMMENT:AnalysisContract.Source.DANMAKU);
        if(withVideo)selected.add(AnalysisContract.Source.VIDEO);
        var spec=new TaskTypes.Specification(audience?"audience.report":"danmaku.report",
                Map.of("video_id",1001,"start",end.minusDays(1).toString(),"end",end.toString()),Map.of(),new AnalysisContract(selected,true));
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.UNKNOWN,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec),null,null));
        run.task(new TaskAssembler(new SkillCatalog(),new PromptCatalog(),new ToolCatalog()).assemble(run));
    }
    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void emptyFeedbackStillRequiresExactlyTheSelectedSourcesBeforeAStoredReport(boolean audience,boolean withVideo) {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var repository=new MemoryRunRepository();
            var run=repository.create("已确认范围的反馈报告",new AccessScope("t","u",Set.of(1001L)),null,null,null).run();
            prepareFeedbackReport(run,audience,withVideo);
            var q=((TaskConditions.Report)run.task().specification().typed()).query();
            var bins=java.util.stream.IntStream.range(0,12).mapToObj(i->new DanmakuTypes.Bucket(i,0)).toList();
            var empty=new DanmakuTypes.Dataset(q,0,0,bins,List.of(),Instant.now(),"fixture",false,new AudienceData.Overview(List.of(),List.of(),0,List.of(),0,0));
            if(audience)empty=AudienceReportData.combine(new AudienceReportData.Snapshot(null,new VideoComments.Dataset("video-comments-v1",q,0,0,0,List.of(),Instant.now(),"fixture"),0),"u",Set.of(AnalysisContract.Source.COMMENT));
            var evidence=new BusinessEvidencePolicy().registerDanmakuEvidence(run,empty);
            var tools=HarnessLifecycleTest.dispatcher(new SkillCatalog(),(query,scope)->{throw new AssertionError("No extra query is needed for a frozen empty snapshot");},fixture.harness,repository,repository);
            tools.execute(new ToolCall("read_skill",Map.of("name",audience?"audience-report":"danmaku-analysis")),run);
            var args=new HashMap<String,Object>(Map.of("evidence_id",evidence.id()));
            if(withVideo) {
                assertEquals("INVALID_REPORT",assertThrows(AgentException.class,()->tools.execute(new ToolCall("create_danmaku_report",args),run)).code());
                assertFalse(run.execution().hasReport(evidence.id()));
                var video=new BusinessEvidencePolicy().registerVideoEvidence(run,video());args.put("video_evidence_id",video.id());
            } else {
                assertEquals("TASK_CONSTRAINT_VIOLATION",assertThrows(AgentException.class,()->tools.execute(new ToolCall("analyze_video_content",Map.of("video_id",1001)),run)).code());
            }
            var result=tools.execute(new ToolCall("create_danmaku_report",args),run);
            assertTrue(run.execution().hasReport(evidence.id()));
            assertTrue(repository.report(run.id,run.owner,result.data().get("artifact_id").toString()).length>0);
            assertTrue(run.execution().classification(evidence.id()).topics().isEmpty());
            var delivery=new DeliveryService(fixture.harness);
            delivery.answer(run,result.terminalAnswer(),delivery.reserve(run),StopReason.TOOL_DELIVERY);
            assertEquals(Status.SUCCEEDED,run.snapshot().status());
        }
    }
    @Test void mixedFeedbackReportCannotBeReplacedByAPureVideoArtifact() {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var repository=new MemoryRunRepository();
            var run=repository.create("已确认视频和评论的报告",new AccessScope("t","u",Set.of(1001L)),null,null,null).run();
            prepareFeedbackReport(run,true,true);
            var video=new BusinessEvidencePolicy().registerVideoEvidence(run,video());
            var tools=HarnessLifecycleTest.dispatcher(new SkillCatalog(),(query,scope)->{throw new AssertionError("Unexpected query");},fixture.harness,repository,repository);
            tools.execute(new ToolCall("read_skill",Map.of("name","video-content")),run);
            assertEquals("INVALID_EVIDENCE",assertThrows(AgentException.class,()->tools.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id",video.id())),run)).code());
            assertFalse(run.execution().hasReport(video.id()));assertEquals(Status.RUNNING,run.snapshot().status());run.cancel();
        }
    }
    @Test void ordinaryMixedCommentAnalysisUsesCommentPagesAndDeliversWithoutAReport() {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var repository=new MemoryRunRepository();
            var run=repository.create("结合视频内容分析视频1001的评论，不需要报告",new AccessScope("t","u",Set.of(1001L)),null,null,null).run();prepare(run);
            assertEquals("comments.analysis",run.task().specification().type());
            assertEquals(Set.of(AnalysisContract.Source.VIDEO,AnalysisContract.Source.COMMENT),run.task().specification().analysis().sources());
            assertFalse(run.task().specification().analysis().reportRequired());
            var tools=HarnessLifecycleTest.dispatcher(new SkillCatalog(),(q,s)->{throw new AssertionError("Unexpected metric query");},fixture.harness,repository,repository);
            for(String skill:List.of("video-content","comment-analysis","audience-report"))tools.execute(new ToolCall("read_skill",Map.of("name",skill)),run);
            var names=tools.definitions(run).stream().map(ToolDefinition::name).toList();
            assertTrue(names.containsAll(List.of("analyze_video_content","query_video_comments")));
            for(String tool:List.of("query_audience","create_danmaku_report")) {
                assertFalse(names.contains(tool));
                assertEquals("TASK_CONSTRAINT_VIOLATION",assertThrows(AgentException.class,()->tools.execute(new ToolCall(tool,Map.of()),run)).code());
            }
            var q=((TaskConditions.Report)run.task().specification().typed()).query();
            var comment=new VideoComments.Comment("1",null,"882","访客","想了解配送情况",q.end().atZone(DanmakuTypes.ZONE).toInstant().minusSeconds(1),true,false,false);
            var comments=new Evidence("ev_comments",run.id,run.owner,null,null,null,new VideoComments.Dataset("video-comments-v1",q,1,1,1,List.of(comment),Instant.now(),"fixture"));
            run.execution().evidence().put(comments);
            var page=tools.execute(new ToolCall("query_video_comments",Map.of("video_id",1001,"start",q.start().toString(),"end",q.end().toString(),"evidence_id",comments.id())),run);
            assertEquals(true,((Map<?,?>)page.data().get("page")).get("complete"));
            assertEquals(1,run.execution().commentOffset(comments.id()));
            var video=new BusinessEvidencePolicy().registerVideoEvidence(run,video());
            var delivery=new DeliveryService(fixture.harness);
            delivery.answer(run,new Answer("评论提出配送问题；视频采样画面中可见鸭子。",List.of(comments.id(),video.id())),delivery.reserve(run),StopReason.ANSWER);
            assertEquals(Status.SUCCEEDED,run.snapshot().status());
            assertFalse(run.execution().hasReport(comments.id()));assertFalse(run.execution().hasReport(video.id()));
        }
    }
    @Test void excludedSourcesCannotBeDiscoveredOrDispatchedEvenAfterSkillActivation() {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var run=new RunState(new AccessScope("t","u",Set.of(1001L)),"视频1001的评论报告，只分析评论");prepare(run);
            run.execution().activateSkill("audience-report");run.execution().activateSkill("video-content");run.execution().activateSkill("danmaku-analysis");
            var names=fixture.tools.definitions(run).stream().map(ToolDefinition::name).toList();
            assertTrue(names.contains("query_audience"));assertFalse(names.contains("query_danmaku"));assertFalse(names.contains("analyze_video_content"));
            for(String tool:List.of("analyze_video_content","query_danmaku"))
                assertEquals("TASK_CONSTRAINT_VIOLATION",assertThrows(AgentException.class,()->fixture.tools.execute(new ToolCall(tool,Map.of("video_id",1001)),run)).code());
            assertEquals(0,fixture.queries.get());assertTrue(run.execution().evidence().isEmpty());run.cancel();
        }
    }
    @Test void deliveryRequiresEverySelectedSourceAndRejectsEvidenceFromExcludedSources() {
        var spec=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve("结合视频内容分析视频1001的评论",LocalDate.now());
        var owner=new AccessScope("t","u",Set.of(1001L));var run=UUID.randomUUID();
        var v=new Evidence("video",run,owner,null,null,video());
        assertEquals("INVALID_EVIDENCE",assertThrows(AgentException.class,()->AnalysisExecutionPolicy.requireComplete(spec,List.of(v))).code());
        var comments=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve("分析视频1001的评论",LocalDate.now());
        assertEquals("TASK_CONSTRAINT_VIOLATION",assertThrows(AgentException.class,()->AnalysisExecutionPolicy.requireComplete(comments,List.of(v))).code());
    }
    @Test void videoOnlyReportMustActuallyPersistBeforeToolDeliveryCanSucceed() {
        try(var fixture=new LoopControlTest.Fixture(LoopControlTest.policy(6))) {
            var repository=new MemoryRunRepository();
            var run=repository.create("生成视频1001的视频内容报告，只看视频内容",new AccessScope("t","u",Set.of(1001L)),null,null,null).run();prepare(run);
            var evidence=new BusinessEvidencePolicy().registerVideoEvidence(run,video());
            var delivery=new DeliveryService(fixture.harness);var reservation=delivery.reserve(run);
            assertEquals("REPORT_REQUIRED",assertThrows(AgentException.class,()->delivery.answer(run,new Answer("报告已生成",List.of(evidence.id())),reservation,StopReason.TOOL_DELIVERY)).code());
            assertFalse(run.execution().hasReport(evidence.id()));
            var unavailable=mock(com.tianmu.agent.runtime.store.ReportStore.class);
            when(unavailable.saveReport(eq(run.id),eq(run.owner),eq(evidence.id()),any(byte[].class))).thenThrow(new AgentException("STORAGE_UNAVAILABLE","fixture"));
            assertThrows(AgentException.class,()->new ReportArtifacts(unavailable).save(run,evidence.id(),new byte[]{1}));
            assertFalse(run.execution().hasReport(evidence.id()));
            var context=mock(ToolContext.class);when(context.evidence(evidence.id())).thenReturn(evidence);
            when(context.analysisContract()).thenReturn(run.task().specification().analysis());
            when(context.saveReport(eq(evidence.id()),any(byte[].class))).thenAnswer(call->new ReportArtifacts(repository).save(run,evidence.id(),call.getArgument(1)));
            var result=new CreateDanmakuReportTool(new ObjectMapper()).execute(new ToolCall("create_danmaku_report",Map.of("evidence_id",evidence.id())),context);
            assertTrue(run.execution().hasReport(evidence.id()));
            String html=new String(repository.report(run.id,run.owner,(String)result.data().get("artifact_id")),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(html.contains("未读取评论和弹幕"));assertTrue(html.contains("&lt;鸭子&gt;"));
            delivery.answer(run,result.terminalAnswer(),reservation,StopReason.TOOL_DELIVERY);
            assertEquals(Status.SUCCEEDED,run.snapshot().status());
        }
    }
    @Test void explicitPlainJointAnalysisCanDeliverWithoutCreatingAnArtifact() {
        try(var fixture=new LoopControlTest.Fixture(LoopControlTest.policy(6))) {
            var run=new RunState(new AccessScope("t","u",Set.of(1001L)),"分析视频1001昨天的评论和弹幕，不需要报告");prepare(run);
            assertFalse(run.task().specification().analysis().reportRequired());
            var q=((TaskConditions.Report)run.task().specification().typed()).query();
            var bins=new ArrayList<DanmakuTypes.Bucket>();for(int i=0;i<12;i++)bins.add(new DanmakuTypes.Bucket(i,0));
            var d=new DanmakuTypes.Dataset(q,0,0,bins,List.of(),Instant.now(),"fixture",false,new AudienceData.Overview(List.of(),List.of(),0,List.of(),0,0));
            var c=new VideoComments.Dataset("video-comments-v1",q,0,0,0,List.of(),Instant.now(),"fixture");
            var data=AudienceReportData.combine(new AudienceReportData.Snapshot(d,c,0),"u");
            var evidence=new BusinessEvidencePolicy().registerDanmakuEvidence(run,data);
            var delivery=new DeliveryService(fixture.harness);
            delivery.answer(run,new Answer("该统计区间内没有保留的评论和弹幕。",List.of(evidence.id())),delivery.reserve(run),StopReason.ANSWER);
            assertEquals(Status.SUCCEEDED,run.snapshot().status());assertFalse(run.execution().hasReport(evidence.id()));
        }
    }
}
