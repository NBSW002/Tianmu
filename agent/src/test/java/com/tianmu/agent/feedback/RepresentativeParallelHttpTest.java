package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.runtime.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual scheduler + tool workers; only remote providers are fixtures. */
@SpringBootTest(properties={"agent.preparation.deterministic-routing=true","agent.feedback.compression.enabled=true","agent.tools.parallel.enabled=true","harness.trace.directory=./var/test-representative-parallel-trace"})
@Import(RepresentativeReportHttpTest.Vectors.class)
class RepresentativeParallelHttpTest {
    @Autowired RunService runs;@Autowired FeedbackAnalysisService analysis;@Autowired FeedbackPreparationService preparation;
    @Autowired com.tianmu.agent.harness.trace.TraceProperties traceProperties;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockBean ModelPort model;@MockBean FeedbackSnapshotPort source;@MockBean com.tianmu.agent.video.VideoAnalysisService video;
    final AccessScope owner=new AccessScope("demo","alice",Set.of(1001L));
    CountDownLatch videoEntered,sourceEntered,release;AtomicBoolean hold,failVideo;AtomicLong preparedBeforeVideo;
    @BeforeEach void script(){
        videoEntered=new CountDownLatch(1);sourceEntered=new CountDownLatch(1);release=new CountDownLatch(1);hold=new AtomicBoolean();failVideo=new AtomicBoolean();preparedBeforeVideo=new AtomicLong();
        when(source.export(any(),any(),any(),any())).thenAnswer(inv->{
            assertTrue(videoEntered.await(3,TimeUnit.SECONDS));sourceEntered.countDown();
            var control=inv.<com.tianmu.agent.runtime.control.CallControl>getArgument(2);
            if(hold.get())try(var ignored=control.cancellation().onCancel(release::countDown)){assertTrue(release.await(5,TimeUnit.SECONDS));control.checkpoint();}
            Request req=inv.getArgument(0);var rows=new ArrayList<Unit>();
            for(var selected:req.sources())for(int i=1;i<=6;i++)rows.add(new Unit((selected==Source.COMMENT?"C:":"D:")+i,selected,""+i,i%2==0?"背景音乐盖住了人声":"声音太小听不清楚","",false,selected==Source.DANMAKU?1000L:null,"viewer"+i,req.query().end().minusSeconds(1).atZone(DanmakuTypes.ZONE).toInstant(),1L));
            inv.<java.util.function.Consumer<List<Unit>>>getArgument(3).accept(rows);
            return new FeedbackSnapshotPort.Receipt(Instant.now(),new Counts(6,6,0,0,6));
        });
        when(video.analyze(anyLong(),any(),any(),anyString(),any(),any())).thenAnswer(inv->{
            videoEntered.countDown();var control=inv.<com.tianmu.agent.runtime.control.CallControl>getArgument(5);
            if(hold.get())try(var ignored=control.cancellation().onCancel(release::countDown)){assertTrue(release.await(5,TimeUnit.SECONDS));control.checkpoint();}
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean ready=false;
            while(System.nanoTime()<until){
                for(var run:runs.running()){var job=analysis.forRun(run.id);if(job!=null && preparation.materialsReady(job.id())){ready=true;break;}}
                if(ready)break;control.checkpoint();Thread.sleep(10);
            }
            assertTrue(ready,"Materials must finish while video and its parent run are still executing");preparedBeforeVideo.set(System.nanoTime());
            if(failVideo.get())throw new AgentException("FORBIDDEN","Video source was revoked");
            return new VideoContent.Facts("video-content-v1",1001,"fixture",0,10000,"采样画面",List.of(),"","SAMPLED_FRAMES",List.of(1000L),"fixture",Instant.now());
        });
        when(model.decide(any(),any())).thenAnswer(inv->{
            ModelContext c=inv.getArgument(0);assertTrue(c.observations().stream().noneMatch(r->r.pending()!=null));
            if(!c.activeSkills().contains("audience-report"))return Decision.tool("read_skill",Map.of("name","audience-report"));
            var material=c.observations().stream().filter(r->r.tool().equals("prepare_feedback_materials")).findFirst();
            if(material.isEmpty())return new Decision(Kind.TOOL,new ToolCall("analyze_video_content",Map.of("video_id",1001),"video","sources"),null,null,List.of(new ToolCall("prepare_feedback_materials",Map.of(),"materials","sources")));
            var visual=c.observations().stream().filter(r->r.tool().equals("analyze_video_content")).findFirst().orElseThrow();
            return Decision.tool("generate_feedback_report",Map.of("materials_id",material.get().data().get("materials_id"),"video_evidence_id",visual.evidenceId()));
        });
    }
    @Test void materialsFinishDuringVideoAndOnlyThenThePairedResultsGenerateReport()throws Exception{
        var run=runs.create("结合视频1001的视频内容、评论和弹幕生成观众反馈报告",owner);awaitTerminal(run);
        assertEquals(Status.SUCCEEDED,run.snapshot().status(),()->String.valueOf(run.snapshot().errorCode()));assertTrue(preparedBeforeVideo.get()>0);
        assertEquals(List.of("video","materials"),run.execution().exchanges().stream().map(ToolExchange::call).filter(c->"sources".equals(c.providerGroupId())).map(ToolCall::providerCallId).toList());
        assertEquals(1,run.execution().observations().stream().filter(r->r.tool().equals("generate_feedback_report")).count());
        List<com.tianmu.agent.harness.trace.TraceEnvelope> spans=List.of();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<until){
            var found=new ArrayList<com.tianmu.agent.harness.trace.TraceEnvelope>();
            var path=traceProperties.directory().resolve("archive/spans.jsonl");
            if(java.nio.file.Files.exists(path))for(var line:java.nio.file.Files.readAllLines(path)){
                if(line.isBlank())continue;var span=json.readValue(line,com.tianmu.agent.harness.trace.TraceEnvelope.class);if(run.snapshot().traceId().equals(span.traceId()))found.add(span);
            }
            spans=found;if(spans.stream().anyMatch(s->s.name().equals("feedback.materials.deliver")))break;Thread.sleep(10);
        }
        var videoSpan=spans.stream().filter(s->s.name().equals("agent.tool") && "analyze_video_content".equals(s.attributes().get("tool.name"))).findFirst().orElseThrow();
        var materialSpan=spans.stream().filter(s->s.name().equals("feedback.prepare") && "MATERIALS".equals(s.attributes().get("analysis.phase"))).findFirst().orElseThrow();
        var join=spans.stream().filter(s->s.name().equals("feedback.materials.deliver")).findFirst().orElseThrow();
        assertEquals("PARALLEL",videoSpan.attributes().get("tool.execution_mode"));
        assertTrue(videoSpan.startTimeUnixNano()<materialSpan.endTimeUnixNano() && materialSpan.endTimeUnixNano()<=videoSpan.endTimeUnixNano());
        assertTrue(join.startTimeUnixNano()>=videoSpan.endTimeUnixNano());assertEquals(List.of(materialSpan.spanId()),join.attributes().get("analysis.predecessor_span_ids"));
        assertEquals(videoSpan.attributes().get("tool.batch_id"),join.attributes().get("tool.join_batch_id"));
        assertEquals("sources",join.attributes().get("tool.join_group_id"));
    }
    @Test void cancellationDuringBothBranchesLeavesNoReportAndSettlesPreparation()throws Exception{
        hold.set(true);var run=runs.create("结合视频1001的视频内容、评论和弹幕生成观众反馈报告",owner);
        try{
            assertTrue(sourceEntered.await(8,TimeUnit.SECONDS));run.cancel();awaitTerminal(run);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(analysis.forRun(run.id).phase()!=Phase.CANCELLED && System.nanoTime()<until)Thread.sleep(10);
            assertEquals(Status.CANCELLED,run.snapshot().status());assertEquals(Phase.CANCELLED,analysis.forRun(run.id).phase());
            assertTrue(run.execution().observations().stream().noneMatch(r->r.tool().equals("generate_feedback_report")));assertTrue(run.execution().evidence().snapshot().isEmpty());
        }finally{release.countDown();run.cancel();}
    }
    @Test void videoFailureAfterMaterialsCompleteNeverDeliversAndOrphanJobIsCancelled()throws Exception{
        failVideo.set(true);var run=runs.create("结合视频1001的视频内容、评论和弹幕生成观众反馈报告",owner);awaitTerminal(run);
        assertEquals(Status.FAILED,run.snapshot().status());assertEquals("FORBIDDEN",run.snapshot().errorCode());assertTrue(preparedBeforeVideo.get()>0);
        var job=analysis.forRun(run.id);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(analysis.forRun(run.id).phase()!=Phase.CANCELLED && System.nanoTime()<until)Thread.sleep(10);
        assertEquals(Phase.CANCELLED,analysis.forRun(run.id).phase());assertTrue(preparation.materialsReady(job.id()));
        assertTrue(run.execution().evidence().snapshot().isEmpty());assertTrue(run.execution().observations().stream().noneMatch(r->r.tool().equals("generate_feedback_report")));
    }
    private void awaitTerminal(RunState run)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<until)Thread.sleep(20);
        assertTrue(AgentTypes.terminal(run.snapshot().status()),"Run did not finish");
    }
}
