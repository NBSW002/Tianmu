package com.tianmu.agent.feedback;

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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"agent.preparation.deterministic-routing=true","harness.trace.directory=./var/test-pipeline-trace"})
@AutoConfigureMockMvc
class FeedbackPipelineHttpTest {
    @Autowired RunService runs;@Autowired MockMvc mvc;@Autowired FeedbackAnalysisService analysis;
    @MockBean ModelPort model;@MockBean FeedbackSnapshotPort source;
    @MockBean FeedbackEmbedding embedding;@MockBean FeedbackVectorIndex vectors;
    final AccessScope owner=new AccessScope("demo","alice",Set.of(1001L));
    final AtomicInteger exports=new AtomicInteger(),embeddings=new AtomicInteger();
    final AtomicBoolean empty=new AtomicBoolean();
    @BeforeEach void script(){
        exports.set(0);embeddings.set(0);empty.set(false);var simultaneous=new CyclicBarrier(2);
        when(embedding.version()).thenReturn("test-embedding:dim3:framing-v1");
        when(embedding.embed(anyList(),any())).thenAnswer(inv->{
            embeddings.incrementAndGet();simultaneous.await(5,TimeUnit.SECONDS);
            List<String> inputs=inv.getArgument(0);return inputs.stream().map(s->List.of(1f,0f,0f)).toList();
        });
        when(vectors.search(any(),anyList(),anyInt(),any())).thenReturn(List.of());
        when(source.export(any(),any(),any(),any())).thenAnswer(inv->{
            exports.incrementAndGet();Request request=inv.getArgument(0);java.util.function.Consumer<List<Unit>> sink=inv.getArgument(3);
            var at=request.query().end().minusSeconds(1).atZone(DanmakuTypes.ZONE).toInstant();
            var rows=new ArrayList<Unit>();
            if(!empty.get())for(var selected:request.sources())for(int i=1;i<=12;i++)rows.add(new Unit((selected==Source.COMMENT?"C:":"D:")+i,selected,""+i,
                    i%2==0?"没声音":"😀","",i==1,selected==Source.DANMAKU?1000L:null,"viewer"+(i%3),at));
            sink.accept(rows);return new FeedbackSnapshotPort.Receipt(Instant.now(),new Counts(empty.get()?0:12,empty.get()?0:12,empty.get()?0:1,empty.get()?0:1,empty.get()?0:3));
        });
        when(model.decide(any(),any())).thenAnswer(inv->{
            ModelContext c=inv.getArgument(0);
            assertTrue(c.observations().stream().noneMatch(r->r.pending()!=null),"The model must never poll a pending child");
            if(!c.activeSkills().contains("audience-report"))return Decision.tool("read_skill",Map.of("name","audience-report"));
            var comments=c.observations().stream().filter(r->r.tool().equals("analyze_comments")).findFirst();
            var danmaku=c.observations().stream().filter(r->r.tool().equals("analyze_danmaku")).findFirst();
            if(comments.isEmpty() && danmaku.isEmpty())return new Decision(Kind.TOOL,new ToolCall("analyze_comments",Map.of()),null,null,List.of(new ToolCall("analyze_danmaku",Map.of())));
            assertTrue(comments.isPresent() && danmaku.isPresent(),"Both source results resume together");
            return Decision.tool("create_feedback_report",Map.of("comment_evidence_id",comments.get().evidenceId(),"danmaku_evidence_id",danmaku.get().evidenceId()));
        });
    }
    @Test void parallelSourcesShareSnapshotAndReportIsSavedBeforeDelivery() throws Exception {run(false);}
    @Test void emptySourcesStillSaveAReportWithoutEmbedding() throws Exception {run(true);}
    void run(boolean noData)throws Exception {
        empty.set(noData);var run=runs.create("只根据视频1001的评论和弹幕生成报告，不读取视频内容",owner);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<end)Thread.sleep(20);
        assertEquals(Status.SUCCEEDED,run.snapshot().status(),run.snapshot().errorCode());assertEquals(1,exports.get());
        assertEquals(noData?0:2,embeddings.get());assertEquals(3,run.snapshot().modelCalls());
        var result=run.execution().observations().stream().filter(r->r.tool().equals("create_feedback_report")).findFirst().orElseThrow();
        assertEquals("READY",result.data().get("status"));assertTrue(run.execution().hasReport(result.evidenceId()));
        var job=analysis.forRun(run.id);assertEquals(FeedbackPreparationService.VERSION,job.request().rulesVersion());
        assertEquals(noData?0:24,job.snapshot().counts().total());assertEquals(noData?0:3,job.snapshot().counts().uniqueAudience());
        mvc.perform(get(result.data().get("url").toString()).header("Authorization","Bearer demo-alice")).andExpect(status().isOk());
        mvc.perform(get(result.data().get("url").toString())).andExpect(status().isUnauthorized());
        assertThrows(AgentException.class,()->analysis.asset(job.id(),new AccessScope("demo","bob",Set.of(1001L)),job.request()));
    }
}
