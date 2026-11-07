package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepresentativeReportPipelineTest {
    @TempDir Path directory;
    @Test void realProviderProjectionOnlyReadsRepresentativesAndPersistsCanonicalJson() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var requests=new CopyOnWriteArrayList<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{try{
            String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);requests.add(body);
            var wire=json.readTree(body);var payload=json.readTree(wire.path("messages").get(wire.path("messages").size()-1).path("content").asText());
            var assignments=new LinkedHashMap<String,String>();for(var g:payload.path("groups"))assignments.put(g.path("id").asText(),"T1");
            var content=Map.of("topics",List.of(Map.of("key","T1","title","声音清晰度","observation","代表反馈认为配乐影响人声辨识。","recommendation","建议检查配乐和人声的平衡。")),"assignments",assignments);
            byte[] result=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(content)))),"usage",Map.of("prompt_tokens",300,"completion_tokens",80)));
            exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);
        }finally{exchange.close();}});server.start();
        try {
            var properties=new DeepSeekProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture","fixture","fixture",Duration.ofSeconds(2),384,1536,"fixture",false);
            var models=new DeepSeekFeedbackModels(new DeepSeekClient(properties,json),properties,json,new PromptCatalog());
            var jobs=new MemoryFeedbackJobStore();var steps=new MemoryFeedbackPreparationStore();var objects=new FeedbackObjects(new FileObjectStore(directory),json);
            var owner=new AccessScope("tenant","creator",Set.of(1001L));var rows=new ArrayList<Unit>();
            rows.add(unit(1,Source.COMMENT,"背景音乐盖住人声了",120L));
            for(int i=2;i<=22;i++)rows.add(unit(i,Source.COMMENT,"音乐太大听不清楚",1L));
            rows.add(unit(23,Source.COMMENT,"太吵",0L));rows.add(unit(24,Source.DANMAKU,"手机外放听不清楚",null));
            var exports=new AtomicInteger();FeedbackSnapshotPort source=new FeedbackSnapshotPort(){
                public void authorize(AccessScope scope,long video){if(!scope.equals(owner) || video!=1001)throw new AgentException("FORBIDDEN","fixture");}
                public Receipt export(Request r,AccessScope scope,CallControl c,java.util.function.Consumer<List<Unit>> sink){authorize(scope,r.query().videoId());exports.incrementAndGet();sink.accept(rows);return new Receipt(Instant.now(),new Counts(23,1,0,0,24));}
            };
            var service=new FeedbackAnalysisService(jobs,objects,source,models,FeedbackPolicy.defaults());
            FeedbackEmbedding embedding=new FeedbackEmbedding(){public String version(){return "fixture";}public int dimension(){return 3;}public List<List<Float>> embed(List<String> t,CallControl c){return t.stream().map(v->List.of(1f,0f,0f)).toList();}};
            var vectors=mock(FeedbackVectorIndex.class);when(vectors.dimension()).thenReturn(3);when(vectors.fetch(any(),anyList(),any())).thenReturn(Map.of());
            var policy=new FeedbackCompressionProperties(true,3,15000,.90,32,.75,2,3,60,.96,300,600,32768,67108864,512);
            var preparation=new FeedbackPreparationService(service,steps,objects,FeedbackPolicy.defaults(),new FeedbackClusters(null,null),FeedbackRuntimeProperties.defaults(),new FeedbackCompression(embedding,vectors,policy,json));
            var request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),Set.of(Source.COMMENT,Source.DANMAKU),Grounding.VIDEO_GROUNDED,0L,10000L,FeedbackCompression.VERSION);
            var run=UUID.randomUUID();var job=preparation.requestMaterials(run,owner,request,Instant.now().plusSeconds(90));
            for(int n=0;n<10 && !preparation.materialsReady(job.id());n++)for(var step:preparation.pending(job.id()))preparation.advance(job.id(),step,new CancellationToken());
            var control=new CallControl(Instant.now().plusSeconds(90),new CancellationToken());var material=preparation.materials(job.id(),owner,control);
            assertEquals(2,material.representativeCount());assertEquals(1,material.excluded().size());assertEquals(23,material.eligibleCounts().total());
            var video=new VideoContent.Facts("video-content-v1",1001,"version",0,10000,"摄影教程",List.of(new VideoContent.Moment(1000,"演示参数")),"","SAMPLED_FRAMES",List.of(1000L),"fixture",Instant.now());
            preparation.startCompressedReport(job.id(),owner,video,control);
            for(int n=0;n<12 && jobs.get(job.id()).phase()!=Phase.READY;n++)service.advance(job.id());
            assertEquals(Phase.READY,jobs.get(job.id()).phase(),()->String.valueOf(jobs.get(job.id()).errorCode()));
            var report=preparation.reportJson(job.id(),owner,control);
            assertEquals(1,exports.get());assertEquals(1,requests.size());assertFalse(requests.get(0).contains("音乐太大听不清楚"));assertFalse(requests.get(0).contains("originalIds"));assertTrue(requests.get(0).contains("背景音乐盖住人声了"));
            assertEquals(22,report.topics().get(0).commentCount());assertEquals(1,report.topics().get(0).danmakuCount());assertEquals(2,report.topics().get(0).representatives().size());
            assertEquals(report,preparation.storedReportJson(job.id(),owner));assertTrue(new String(report.html(),java.nio.charset.StandardCharsets.UTF_8).contains("未逐条阅读全部原文"));
            var asset=service.asset(job.id(),owner,request);
            assertEquals("REPRESENTATIVE_ONLY",assertThrows(AgentException.class,()->service.search(asset,"音乐",0,5)).code());
            assertEquals("REPRESENTATIVE_ONLY",assertThrows(AgentException.class,()->service.originalText(asset,report.topics().get(0).id(),"C:2",0,50)).code());
            assertEquals("REPRESENTATIVE_ONLY",assertThrows(AgentException.class,()->service.read(asset,"originals",report.topics().get(0).id(),0,5)).code());
            assertEquals("FORBIDDEN",assertThrows(AgentException.class,()->preparation.storedReportJson(job.id(),new AccessScope("tenant","creator",Set.of()))).code());
        } finally {server.stop(0);}
    }
    private Unit unit(int id,Source source,String text,Long likes){return new Unit((source==Source.COMMENT?"C:":"D:")+id,source,""+id,text,"",false,source==Source.DANMAKU?1000L:null,"viewer"+id,Instant.parse("2026-11-01T01:00:00Z"),likes);}
}
