package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.runtime.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"agent.preparation.deterministic-routing=true","agent.policy-snapshot.enabled=true","agent.feedback.compression.enabled=true","harness.trace.directory=./var/test-representative-http-trace"})
@AutoConfigureMockMvc
@Import(RepresentativeReportHttpTest.Vectors.class)
class RepresentativeReportHttpTest {
    @TestConfiguration static class Vectors {
        @Bean FeedbackEmbedding representativeEmbedding(){return new FeedbackEmbedding(){public String version(){return "fixture-text-v1";}public int dimension(){return 3;}public List<List<Float>> embed(List<String> input,com.tianmu.agent.runtime.control.CallControl c){return input.stream().map(t->List.of(1f,0f,0f)).toList();}};}
        @Bean FeedbackVectorIndex representativeVectors(){var v=mock(FeedbackVectorIndex.class);when(v.dimension()).thenReturn(3);when(v.fetch(any(),anyList(),any())).thenReturn(Map.of());return v;}
    }
    @Autowired RunService runs;@Autowired MockMvc mvc;@Autowired FeedbackAnalysisService analysis;
    @MockBean ModelPort model;@MockBean FeedbackSnapshotPort source;
    final AccessScope owner=new AccessScope("demo","alice",Set.of(1001L));
    final AtomicBoolean empty=new AtomicBoolean(),single=new AtomicBoolean(),authorized=new AtomicBoolean(true);
    @BeforeEach void script(){
        empty.set(false);single.set(false);authorized.set(true);
        doAnswer(inv->{if(!authorized.get())throw new AgentException("FORBIDDEN","fixture revoked");return null;}).when(source).authorize(any(),anyLong());
        when(source.export(any(),any(),any(),any())).thenAnswer(inv->{Request request=inv.getArgument(0);java.util.function.Consumer<List<Unit>> sink=inv.getArgument(3);var rows=new ArrayList<Unit>();
            if(!empty.get())for(var s:request.sources())for(int i=1;i<=(single.get()?(s==Source.COMMENT?1:0):20);i++)rows.add(new Unit((s==Source.COMMENT?"C:":"D:")+i,s,""+i,i==1?"背景音乐盖住了人声":"声音太小听不清楚","",false,s==Source.DANMAKU?1000L:null,"viewer"+i,request.query().end().minusSeconds(1).atZone(DanmakuTypes.ZONE).toInstant(),i==1?120L:1L));
            sink.accept(rows);return new FeedbackSnapshotPort.Receipt(Instant.now(),new Counts(rows.stream().filter(r->r.source()==Source.COMMENT).count(),rows.stream().filter(r->r.source()==Source.DANMAKU).count(),0,0,rows.stream().map(Unit::userId).distinct().count()));});
        when(model.decide(any(),any())).thenAnswer(inv->{ModelContext c=inv.getArgument(0);
            assertTrue(c.observations().stream().noneMatch(r->r.pending()!=null));
            if(!c.activeSkills().contains("audience-report"))return Decision.tool("read_skill",Map.of("name","audience-report"));
            assertTrue(c.availableTools().stream().noneMatch(t->Set.of("analyze_comments","analyze_danmaku","create_feedback_report").contains(t.name())));
            var materials=c.observations().stream().filter(r->r.tool().equals("prepare_feedback_materials")).findFirst();
            if(materials.isEmpty())return Decision.tool("prepare_feedback_materials",Map.of());
            assertFalse(materials.get().data().toString().contains("背景音乐"),"Main Agent must receive references, not material bodies");
            return Decision.tool("generate_feedback_report",Map.of("materials_id",materials.get().data().get("materials_id")));
        });
    }
    @Test void threeToolWorkflowSavesStructuredJsonAndEnforcesReportAccess()throws Exception{exercise(false);}
    @Test void emptySnapshotProducesValidJsonWithoutInventingTopics()throws Exception{exercise(true);}
    @Test void aSingleCommentAndNoDanmakuProduceASingleObservation()throws Exception{
        single.set(true);var run=runs.create("只根据视频1001的评论和弹幕生成报告，不读取视频内容",owner);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<end)Thread.sleep(20);
        assertEquals(Status.SUCCEEDED,run.snapshot().status(),()->String.valueOf(run.snapshot().errorCode()));
        var receipt=run.execution().observations().stream().filter(r->r.tool().equals("generate_feedback_report")).findFirst().orElseThrow();
        mvc.perform(get(receipt.data().get("json_url").toString()).header("Authorization","Bearer demo-alice")).andExpect(status().isOk()).andExpect(jsonPath("$.method.raw").value(1)).andExpect(jsonPath("$.method.unclassified").value(1)).andExpect(jsonPath("$.unclassifiedExamples[0].text").value("背景音乐盖住了人声"));
        var asset=analysis.historicalAsset(analysis.forRun(run.id).id(),owner);var summary=analysis.read(asset,"summary",0,1);
        assertTrue(summary.containsKey("assigned_counts"));assertFalse(summary.containsKey("counts"));assertTrue(summary.toString().contains("unclassified"));assertFalse(summary.toString().contains("背景音乐"));
    }
    private void exercise(boolean noData)throws Exception{
        empty.set(noData);var run=runs.create("只根据视频1001的评论和弹幕生成报告，不读取视频内容",owner);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<end)Thread.sleep(20);
        assertEquals(Status.SUCCEEDED,run.snapshot().status(),()->String.valueOf(run.snapshot().errorCode()));
        assertTrue(run.task().resourceVersions().containsKey(com.tianmu.agent.application.TaskAssembler.COMPRESSION_RESOURCE));
        var receipt=run.execution().observations().stream().filter(r->r.tool().equals("generate_feedback_report")).findFirst().orElseThrow();
        String url=receipt.data().get("json_url").toString();
        mvc.perform(get(url).header("Authorization","Bearer demo-alice")).andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value("feedback-report-json-v1")).andExpect(jsonPath("$.method.raw").value(noData?0:40)).andExpect(jsonPath("$.method.representatives").value(noData?0:2));
        mvc.perform(get(url)).andExpect(status().isUnauthorized());mvc.perform(get(url).header("Authorization","Bearer demo-bob")).andExpect(result->assertTrue(Set.of(403,404).contains(result.getResponse().getStatus())));
        mvc.perform(get(url.replace("rp_","rp_0000")).header("Authorization","Bearer demo-alice")).andExpect(result->assertTrue(Set.of(400,404).contains(result.getResponse().getStatus())));
        authorized.set(false);mvc.perform(get(url).header("Authorization","Bearer demo-alice")).andExpect(status().isForbidden());
    }
}
