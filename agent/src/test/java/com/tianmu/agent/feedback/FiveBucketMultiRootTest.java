package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FiveBucketMultiRootTest {
    @TempDir Path directory;
    @Test void distinctBucketsAcrossTwoBatchesRemainDistinctAndKeepAllCounts(){
        var json=new ObjectMapper().findAndRegisterModules();var objects=new FeedbackObjects(new FileObjectStore(directory),json);
        var jobs=new MemoryFeedbackJobStore();var steps=new MemoryFeedbackPreparationStore();var owner=new AccessScope("t","u",Set.of(1001L));var at=Instant.parse("2026-11-01T01:00:00Z");
        var rows=new ArrayList<Unit>();for(int i=0;i<4;i++)rows.add(new Unit("C:"+(i+1),Source.COMMENT,""+(i+1),(i<2?"甲":"乙")+"长文本".repeat(140)+i,"",false,null,"v"+i,at,1L));
        FeedbackSnapshotPort source=new FeedbackSnapshotPort(){public void authorize(AccessScope caller,long video){}public Receipt export(Request r,AccessScope caller,CallControl c,java.util.function.Consumer<List<Unit>> sink){sink.accept(rows);return new Receipt(Instant.now(),new Counts(4,0,0,0,4));}};
        var calls=new AtomicInteger();FeedbackModels models=new FeedbackModels(){public String modelName(){return "fixture";}public String policyHash(){return "fixture";}public List<Proposal> analyze(Input input,CallControl control){calls.incrementAndGet();return List.of(new Proposal("T1","主题","观察","建议",input.groups().stream().map(Group::id).toList()));}};
        FeedbackEmbedding embedding=new FeedbackEmbedding(){public String version(){return "fixture";}public int dimension(){return 2;}public List<List<Float>> embed(List<String> values,CallControl c){return values.stream().map(v->v.startsWith("甲")?List.of(1f,0f):List.of(0f,1f)).toList();}};
        var vectors=mock(FeedbackVectorIndex.class);when(vectors.dimension()).thenReturn(2);when(vectors.fetch(any(),anyList(),any())).thenReturn(Map.of());
        var policy=new FeedbackCompressionProperties(true,3,15000,.9,32,.75,2,3,60,.96,300,600,32768,67108864,512,2000);
        var compression=new FeedbackCompression(embedding,vectors,policy,json);var analysis=new FeedbackAnalysisService(jobs,objects,source,models,FeedbackPolicy.defaults());
        var preparation=new FeedbackPreparationService(analysis,steps,objects,FeedbackPolicy.defaults(),new FeedbackClusters(null,null),FeedbackRuntimeProperties.defaults(),compression);
        var request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        var job=preparation.requestMaterials(UUID.randomUUID(),owner,request,Instant.now().plusSeconds(90));
        for(int n=0;n<10 && !preparation.materialsReady(job.id());n++)for(var step:preparation.pending(job.id()))preparation.advance(job.id(),step,new CancellationToken());
        var control=new CallControl(Instant.now().plusSeconds(90),new CancellationToken());var material=preparation.materials(job.id(),owner,control);
        assertEquals(2,material.clusters().size());assertEquals(2,material.inputs().size());assertEquals(2,material.representativeCount());
        preparation.startCompressedReport(job.id(),owner,null,control);for(int n=0;n<20 && jobs.get(job.id()).phase()!=Phase.READY;n++)analysis.advance(job.id());
        assertEquals(Phase.READY,jobs.get(job.id()).phase());var report=preparation.reportJson(job.id(),owner,control);
        assertEquals(2,calls.get());assertEquals(2,report.topics().size());assertEquals(4,report.topics().stream().mapToLong(FeedbackReportJson.TopicView::commentCount).sum());assertTrue(report.unclassifiedExamples().isEmpty());
    }
}
