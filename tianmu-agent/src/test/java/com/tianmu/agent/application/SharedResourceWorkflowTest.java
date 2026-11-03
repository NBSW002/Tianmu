package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.query.VideoSearchPort;
import com.tianmu.agent.runtime.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"agent.preparation.deterministic-routing=true","harness.trace.directory=./var/test-resource-workflow-trace"})
class SharedResourceWorkflowTest {
    @Autowired RunService runs;
    @Autowired com.tianmu.agent.runtime.store.RunRepository repository;
    @Autowired com.tianmu.agent.runtime.store.ReportStore reports;
    @MockBean ModelPort model;
    @MockBean VideoSearchPort search;
    final AccessScope owner=new AccessScope("demo","alice",Set.of(1001L));
    VideoSearchPort.Candidate candidate(String id){return new VideoSearchPort.Candidate(id,"小鸭视频",31000,Instant.parse("2026-10-28T00:00:00Z"));}
    void await(RunState run)throws Exception{long until=System.nanoTime()+25_000_000_000L;while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<until)Thread.sleep(20);assertTrue(AgentTypes.terminal(run.snapshot().status()),run.snapshot().status().name());}
    @Test void uniqueOwnedTitleResolvesBeforeFreezingAndContinuesTheOriginalAnalysis()throws Exception {
        when(search.search(any(),any(),any())).thenReturn(new VideoSearchPort.Page(List.of(candidate("1001")),1,false));
        when(model.decide(any(),any())).thenAnswer(inv->{ModelContext c=inv.getArgument(0);
            assertEquals("comments.analysis",c.task().specification().type());assertEquals(1001L,((Number)c.task().specification().conditions().get("video_id")).longValue());
            if(!c.activeSkills().contains("comment-analysis"))return Decision.tool("read_skill",Map.of("name","comment-analysis"));
            var a=c.observations().stream().filter(r->r.tool().equals("analyze_feedback")).findFirst();
            if(a.isEmpty())return Decision.tool("analyze_feedback",Map.of());
            if(c.observations().stream().noneMatch(r->r.tool().equals("read_result")))return Decision.tool("read_result",Map.of("result_id",a.get().evidenceId(),"section","topics"));
            return Decision.answer("已读取评论主题。",List.of(a.get().evidenceId()));
        });
        var run=runs.create("分析我上周那个小鸭视频的评论",owner);await(run);
        assertEquals(Status.SUCCEEDED,run.snapshot().status(),run.snapshot().errorCode());
    }
    @Test void ambiguousCandidatesRemainDiscoveryAndCannotExposeAnalysisOrChooseTheFirst()throws Exception {
        when(search.search(any(),any(),any())).thenReturn(new VideoSearchPort.Page(List.of(candidate("1001"),candidate("1003")),2,false));
        when(model.decide(any(),any())).thenAnswer(inv->{ModelContext c=inv.getArgument(0);
            assertEquals("resource.lookup",c.task().specification().type());
            assertTrue(c.availableTools().stream().noneMatch(t->t.name().equals("analyze_feedback")));
            if(!c.activeSkills().contains("resource-discovery"))return Decision.tool("read_skill",Map.of("name","resource-discovery"));
            if(c.observations().stream().noneMatch(r->r.tool().equals("search_my_videos")))return Decision.tool("search_my_videos",Map.of("query","小鸭"));
            return Decision.tool("ask_clarification",Map.of("question","找到两个候选视频，请选择视频1001还是视频1003。"));
        });
        var run=runs.create("分析我上周那个小鸭视频的评论",owner);await(run);
        assertEquals(Status.NEEDS_INPUT,run.snapshot().status(),run.snapshot().errorCode());assertEquals(Set.of(1001L),run.owner.videoIds());
        assertTrue(run.execution().observations().stream().anyMatch(r->r.tool().equals("search_my_videos")));
        assertTrue(run.execution().evidence().snapshot().values().stream().anyMatch(e->e.lookup()!=null));
    }
    @Test void existingReportIsReadAndPagedThroughOneCurrentRunReference()throws Exception {
        var old=repository.create("历史视频报告",owner,null,null,null).run();old.start();
        var video=new VideoContent.Facts("video-content-v1",1001,"old",0,10000,"历史画面摘要",List.of(new VideoContent.Moment(1000,"历史片段")),"","SAMPLED_FRAMES",List.of(1000L),"fixture",Instant.parse("2026-10-01T00:00:00Z"));
        String ev="ev_"+UUID.randomUUID();old.execution().evidence().put(new Evidence(ev,old.id,owner,null,null,video));
        var artifact=reports.saveReport(old.id,owner,ev,"<html>old</html>".getBytes());old.succeed(new Answer("已生成",List.of(ev)));
        when(model.decide(any(),any())).thenAnswer(inv->{ModelContext c=inv.getArgument(0);
            if(!c.activeSkills().contains("resource-discovery"))return Decision.tool("read_skill",Map.of("name","resource-discovery"));
            var result=c.observations().stream().filter(r->r.tool().equals("read_report")).findFirst();
            if(result.isEmpty())return Decision.tool("read_report",Map.of("run_id",old.id.toString(),"artifact_id",artifact.artifactId()));
            assertEquals(true,result.get().data().get("historical"));assertEquals("2026-10-01T00:00:00Z",result.get().data().get("data_as_of"));
            if(c.observations().stream().noneMatch(r->r.tool().equals("read_result")))return Decision.tool("read_result",Map.of("result_id",result.get().evidenceId(),"section","topics"));
            return Decision.answer("以下建议参考上次报告，不代表当前视频状态。",List.of(result.get().evidenceId()));
        });
        var run=runs.create("根据上次报告给我几个选题",owner);await(run);
        assertEquals(Status.SUCCEEDED,run.snapshot().status(),run.snapshot().errorCode());
        assertEquals(1,run.execution().evidence().snapshot().values().stream().filter(e->e.lookup()!=null).count());
        assertTrue(run.snapshot().answer().dataNotes().stream().anyMatch(n->n.label().equals("历史报告参考")));
    }
}
