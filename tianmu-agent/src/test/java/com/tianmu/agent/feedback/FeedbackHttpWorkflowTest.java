package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.runtime.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"agent.preparation.deterministic-routing=true","harness.trace.directory=./var/test-feedback-http-trace"})
@AutoConfigureMockMvc
class FeedbackHttpWorkflowTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired RunService runs;
    @MockBean ModelPort model;@MockBean FeedbackSnapshotPort source;
    @MockBean com.tianmu.agent.video.VideoAnalysisService video;
    @Autowired com.tianmu.agent.interaction.CreatorDraftStore drafts;
    final AccessScope owner=new AccessScope("demo","alice",Set.of(1001L));
    final AtomicBoolean observedWait=new AtomicBoolean();
    final AtomicBoolean empty=new AtomicBoolean();
    @BeforeEach void script(){
        empty.set(false);observedWait.set(false);
        when(video.analyze(anyLong(),any(),any(),anyString(),any(),any())).thenReturn(new VideoContent.Facts("video-content-v1",1001,"fixture",0,10000,"采样小鸭画面",List.of(),"","SAMPLED_FRAMES",List.of(1000L),"fixture",Instant.now()));
        when(source.export(any(),any(),any(),any())).thenAnswer(inv->{
            java.util.function.Consumer<List<Unit>> sink=inv.getArgument(3);var units=new ArrayList<Unit>();
            Request request=inv.getArgument(0);
            if(empty.get())return new FeedbackSnapshotPort.Receipt(Instant.now(),new Counts(0,0,0,0,0));
            for(int i=1;i<=100;i++){var s=i%2==0?Source.COMMENT:Source.DANMAKU;if(request.sources().contains(s))units.add(new Unit((i%2==0?"C:":"D:")+i,s,""+i,"观众问题"+i,"",false,i%2==0?null:1000L,""+i));}
            sink.accept(units);return new FeedbackSnapshotPort.Receipt(Instant.now(),new Counts(request.sources().contains(Source.COMMENT)?50:0,request.sources().contains(Source.DANMAKU)?50:0,0,0,units.size()));
        });
        doAnswer(inv->{
            ModelContext c=inv.getArgument(0);
            if(!c.activeSkills().contains("audience-report"))return Decision.tool("read_skill",Map.of("name","audience-report"));
            var visual=c.observations().stream().filter(r->r.tool().equals("analyze_video_content")).findFirst();
            if(c.task().specification().reading().includes(AnalysisContract.Source.VIDEO) && visual.isEmpty())return Decision.tool("analyze_video_content",Map.of("video_id",1001));
            var result=c.observations().stream().filter(r->r.tool().equals("analyze_feedback")).findFirst();
            if(result.isEmpty())return Decision.tool("analyze_feedback",visual.isPresent()?Map.of("video_evidence_id",visual.get().evidenceId()):Map.of());
            assertNull(result.get().pending(),"No model is polled while an operation is pending");
            assertEquals("READY",result.get().data().get("status"));
            if(!c.task().specification().reading().reportRequired()) {
                if(c.observations().stream().noneMatch(r->r.tool().equals("read_result")))return Decision.tool("read_result",Map.of("result_id",result.get().evidenceId(),"section","topics"));
                return Decision.answer("已读取后台完整归纳的评论主题。",List.of(result.get().evidenceId()));
            }
            return Decision.tool("create_feedback_report",Map.of("evidence_id",result.get().evidenceId()));
        }).when(model).decide(any(),any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void emptyFeedbackUsesTheSameServiceAndStillPersistsAReportIncludingSelectedVideo(boolean withVideo)throws Exception {
        empty.set(true);
        var run=runs.create("视频1001的评论和弹幕报告，"+(withVideo?"结合视频画面":"不读取视频内容"),owner);
        await(run);assertEquals(Status.SUCCEEDED,run.snapshot().status(),run.snapshot().errorCode());
        var evidence=run.execution().evidence().snapshot().values().stream().filter(e->e.feedback()!=null).findFirst().orElseThrow();
        assertEquals(0,evidence.feedback().counts().total());assertTrue(run.execution().hasReport(evidence.id()));
        assertEquals(withVideo?Grounding.VIDEO_GROUNDED:Grounding.FEEDBACK_ONLY,evidence.feedback().request().grounding());
        var report=run.execution().observations().stream().filter(r->r.tool().equals("create_feedback_report")).findFirst().orElseThrow();
        String html=mvc.perform(get(report.data().get("url").toString()).header("Authorization","Bearer demo-alice")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("本次没有反馈记录"));assertEquals(withVideo,html.contains("视频依据"));
        assertFalse(run.execution().exchanges().stream().anyMatch(e->Set.of("query_danmaku","query_audience","create_danmaku_report").contains(e.call().name())));
    }
    @Test void plainCommentAnalysisUsesBackgroundTopicsAndDoesNotCreateAnArtifact()throws Exception {
        var run=runs.create("分析视频1001的评论，不需要报告，也不读取视频内容",owner);await(run);
        assertEquals(Status.SUCCEEDED,run.snapshot().status(),run.snapshot().errorCode());
        var evidence=run.execution().evidence().snapshot().values().stream().filter(e->e.feedback()!=null).findFirst().orElseThrow();
        assertEquals(Set.of(Source.COMMENT),evidence.feedback().request().sources());assertFalse(run.execution().hasReport(evidence.id()));
        assertTrue(run.execution().observations().stream().anyMatch(r->r.tool().equals("read_result")));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"NONE","summary","topics"})
    void readyMetadataOrAnEmptyLastPageCannotSubstituteForReadingTopics(String section)throws Exception {
        doAnswer(inv->{
            ModelContext c=inv.getArgument(0);
            if(!c.activeSkills().contains("audience-report"))return Decision.tool("read_skill",Map.of("name","audience-report"));
            var ready=c.observations().stream().filter(r->r.tool().equals("analyze_feedback")).findFirst();
            if(ready.isEmpty())return Decision.tool("analyze_feedback",Map.of());
            if(!section.equals("NONE") && c.observations().stream().noneMatch(r->r.tool().equals("read_result")))
                return Decision.tool("read_result",Map.of("result_id",ready.get().evidenceId(),"section",section,"offset",section.equals("topics")?999:0));
            return Decision.answer("直接给出尚未读取的反馈结论。",List.of(ready.get().evidenceId()));
        }).when(model).decide(any(),any());
        var run=runs.create("分析视频1001的评论，不需要报告，也不读取视频内容",owner);await(run);
        assertEquals(Status.FAILED,run.snapshot().status());assertEquals("INVALID_EVIDENCE",run.snapshot().errorCode());
        assertNull(run.snapshot().answer());
    }
    void await(RunState run)throws InterruptedException {
        long until=System.nanoTime()+20_000_000_000L;
        while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<until)Thread.sleep(20);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"COMMENT","DANMAKU"})
    void feedbackDrivenDraftsReuseAnalysisWithIndependentToolsAndItemReferences(String kind)throws Exception{
        when(video.analyze(anyLong(),any(),any(),anyString(),any(),any())).thenReturn(new VideoContent.Facts("video-content-v1",1001,"fixture",0,10000,"水边的小鸭",List.of(),"","SAMPLED_FRAMES",List.of(1000L),"fixture",Instant.now()));
        doAnswer(inv->{
            ModelContext c=inv.getArgument(0);String skill=kind.equals("COMMENT")?"smart-comment":"smart-danmaku";
            if(!c.activeSkills().contains(skill))return Decision.tool("read_skill",Map.of("name",skill));
            var v=c.observations().stream().filter(r->r.tool().equals("analyze_video_content")).findFirst();if(v.isEmpty())return Decision.tool("analyze_video_content",Map.of("video_id",1001));
            var a=c.observations().stream().filter(r->r.tool().equals("analyze_feedback")).findFirst();if(a.isEmpty())return Decision.tool("analyze_feedback",Map.of("video_evidence_id",v.get().evidenceId()));
            var topics=c.observations().stream().filter(r->r.tool().equals("read_result")).findFirst();if(topics.isEmpty())return Decision.tool("read_result",Map.of("result_id",a.get().evidenceId(),"section","topics"));
            var first=(Map<?,?>)((List<?>)topics.get().data().get("topics")).get(0);var item=new HashMap<String,Object>();item.put("text","下次你想看什么小鸭日常？");item.put("feedback_topic_id",first.get("id"));if(kind.equals("DANMAKU"))item.put("playback_ms",1000);
            return Decision.tool(kind.equals("COMMENT")?"prepare_comment_draft":"prepare_danmaku_draft",Map.of("video_id",1001,"video_evidence_id",v.get().evidenceId(),"feedback_evidence_id",a.get().evidenceId(),"items",List.of(item)));
        }).when(model).decide(any(),any());
        var run=runs.create("根据视频1001的评论反馈生成一条"+(kind.equals("COMMENT")?"评论":"弹幕")+"草稿",owner);
        long until=System.nanoTime()+20_000_000_000L;while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<until)Thread.sleep(20);
        assertEquals(Status.SUCCEEDED,run.snapshot().status(),run.snapshot().errorCode());var draft=drafts.get(run.id);
        assertEquals(kind,draft.kind());assertEquals("DRAFT",draft.state());assertNotNull(draft.basis().feedbackEvidenceId());assertEquals(1,draft.basis().itemTopics().size());
        assertEquals(Set.of(Source.COMMENT),run.execution().evidence().snapshot().get(draft.basis().feedbackEvidenceId()).feedback().request().sources());
    }
    @Test void oneHttpRunWaitsWithoutPollingTheModelAndOnlySucceedsAfterReportStorage()throws Exception{
        String request=json.writeValueAsString(Map.of("message","只根据视频1001的评论和弹幕生成报告，不读取视频内容"));
        var response=mvc.perform(post("/api/agent/runs").header("Authorization","Bearer demo-alice").contentType("application/json").content(request))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(json.readTree(response).get("runId").asText());RunState run=runs.owned(id,owner);
        long until=System.nanoTime()+20_000_000_000L;
        while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<until){
            if(run.snapshot().status()==Status.WAITING_OPERATION){observedWait.set(true);assertNotNull(run.continuation());}
            Thread.sleep(20);
        }
        assertTrue(observedWait.get());assertEquals(Status.SUCCEEDED,run.snapshot().status(),run.snapshot().errorCode());
        assertEquals(3,run.snapshot().modelCalls(),"Skill, submit, and final report are the only main-agent model calls");
        assertEquals(StopReason.TOOL_DELIVERY,run.snapshot().stopReason());
        var evidence=run.execution().evidence().snapshot().values().stream().filter(e->e.feedback()!=null).findFirst().orElseThrow();
        assertEquals(100,evidence.feedback().counts().total());assertTrue(run.execution().hasReport(evidence.id()));
        String url=run.snapshot().answer().text().split("\\[查看 HTML 报告\\]\\(")[1].split("\\)")[0];
        mvc.perform(get(url).header("Authorization","Bearer demo-alice")).andExpect(status().isOk());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/runs/"+id+"/analysis").header("Authorization","Bearer demo-bob")).andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/runs/"+id+"/analysis").header("Authorization","Bearer demo-alice")).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("READY"));
    }
}
