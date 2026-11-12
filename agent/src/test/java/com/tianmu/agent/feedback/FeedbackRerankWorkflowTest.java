package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.adapters.embedding.QwenFeedbackReranker;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackRerankWorkflowTest {
    @TempDir Path directory;

    @Test void preparesFiveBucketsThenConfirmsAndWritesBeforeDelivery() throws Exception {
        verifyWorkflow(false);
    }
    @Test void directReadingConfirmsAndWritesWithoutEmbeddingOrRerank() throws Exception {
        verifyWorkflow(true);
    }
    @Test void providerFailureCannotDeliverASuccessfulEmptyReport() throws Exception {
        verifyWorkflow(false,true);
    }
    private void verifyWorkflow(boolean direct) throws Exception { verifyWorkflow(direct,false); }
    @Test void writingFailureKeepsConfirmedTopicsButCannotDeliverReport() throws Exception {
        verifyWorkflow(false,false,true);
    }
    private void verifyWorkflow(boolean direct,boolean failProvider) throws Exception {verifyWorkflow(direct,failProvider,false);}
    private void verifyWorkflow(boolean direct,boolean failProvider,boolean failWriter) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var qwenCalls=new AtomicInteger();
        var deepseekCalls=new AtomicInteger();
        var sentEvidence=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/reranks",exchange->{
            try(exchange){
                int call=qwenCalls.incrementAndGet();
                var input=json.readTree(exchange.getRequestBody());
                if(call==2){exchange.sendResponseHeaders(429,-1);return;}
                var results=new ArrayList<Map<String,Object>>();
                for(int index=input.path("documents").size()-1;index>=0;index--)results.add(Map.of("index",index,"relevance_score",index));
                byte[] body=json.writeValueAsBytes(Map.of("model","qwen3-rerank","results",results,"usage",Map.of("total_tokens",100)));
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/chat/completions",exchange->{
            try(exchange){
                int attempt=deepseekCalls.incrementAndGet();
                if(failProvider){exchange.sendResponseHeaders(503,-1);return;}
                var input=json.readTree(exchange.getRequestBody());
                var payload=json.readTree(input.path("messages").get(1).path("content").asText());
                if(payload.has("topics")){
                    if(failWriter){exchange.sendResponseHeaders(503,-1);return;}
                    var output=new ArrayList<Map<String,String>>();
                    for(var topic:payload.path("topics"))output.add(Map.of("topicId",topic.path("topicId").asText(),
                            "observation","报告正文：观众描述了实际体验，原话说明了具体感受。","recommendation",""));
                    var content=json.writeValueAsString(Map.of("topics",output));
                    byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content))),
                            "usage",Map.of("prompt_tokens",200,"completion_tokens",80)));
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return;
                }
                var evidence=payload.path("evidence");
                sentEvidence.set(evidence.size());
                var edges=new ArrayList<Map<String,String>>();
                for(int i=0;i<2;i++)edges.add(Map.of("groupId",evidence.get(i).path("groupId").asText(),"expressionId",evidence.get(i).path("expressionId").asText()));
                var topic=Map.of("title","反馈主题","observation","观众描述了实际体验。","recommendation","",
                        "evidence",edges,"quotes",List.of(attempt==1?evidence.get(0).path("text").asText():edges.get(0).get("expressionId")));
                var content=json.writeValueAsString(Map.of("topics",List.of(topic)));
                byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content))),
                        "usage",Map.of("prompt_tokens",200,"completion_tokens",40)));
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try{
            var base="http://127.0.0.1:"+server.getAddress().getPort();
            var properties=new DeepSeekProperties(URI.create(base),"fixture","fixture","fixture",Duration.ofSeconds(3),384,1536,"fixture",false);
            var models=new DeepSeekFeedbackModels(new DeepSeekClient(properties,json),properties,json,new PromptCatalog());
            var jobs=new MemoryFeedbackJobStore();
            var steps=new MemoryFeedbackPreparationStore();
            var objects=new FeedbackObjects(new FileObjectStore(directory),json);
            var owner=new AccessScope("tenant","creator",Set.of(1001L));
            var rows=new ArrayList<Unit>();
            for(int bucket=0;bucket<5;bucket++)for(int i=0;i<6;i++){
                int id=bucket*6+i+1;
                rows.add(new Unit("C:"+id,Source.COMMENT,""+id,"方向"+bucket+"有具体体验"+i+"观众描述了具体内容和感受".repeat(3),"",false,null,"viewer"+id,Instant.parse("2026-11-01T01:00:00Z"),null));
            }
            FeedbackSnapshotPort source=new FeedbackSnapshotPort(){
                public void authorize(AccessScope scope,long video){
                    if(!scope.equals(owner) || video!=1001)throw new AgentException("FORBIDDEN","fixture");
                }
                public Receipt export(Request r,AccessScope scope,CallControl c,java.util.function.Consumer<List<Unit>> sink){
                    authorize(scope,r.query().videoId());sink.accept(rows);return new Receipt(Instant.now(),new Counts(30,0,0,0,30));
                }
            };
            var service=new FeedbackAnalysisService(jobs,objects,source,models,FeedbackPolicy.defaults());
            service.resultCache(new FeedbackResultCache(json,FeedbackReusePolicy.defaults(),null));
            var embedding=mock(FeedbackEmbedding.class);
            when(embedding.dimension()).thenReturn(5);when(embedding.version()).thenReturn("fixture");
            when(embedding.embed(anyList(),any())).thenAnswer(inv->inv.<List<String>>getArgument(0).stream()
                    .map(text->java.util.stream.IntStream.range(0,5).mapToObj(n->text.contains("方向"+n)?1f:0f).toList()).toList());
            var vectors=mock(FeedbackVectorIndex.class);
            when(vectors.dimension()).thenReturn(5);when(vectors.fetch(any(),anyList(),any())).thenReturn(Map.of());
            var policy=new FeedbackCompressionProperties(true,3,15000,.90,32,.75,2,3,60,.96,300,600,32768,67108864,512);
            var compression=new FeedbackCompression(embedding,vectors,policy,json);
            if(direct)compression.tieredPolicy(new FeedbackTieredProperties(true,32768));
            var prep=new FeedbackPreparationService(service,steps,objects,FeedbackPolicy.defaults(),new FeedbackClusters(null,null),
                    FeedbackRuntimeProperties.defaults(),compression);
            prep.configureRerank(true,new QwenFeedbackReranker(base+"/reranks","fixture",json),FeedbackReusePolicy.defaults());
            var run=UUID.randomUUID();
            var request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),
                    Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,prep.reportRulesVersion(run));
            var control=new CallControl(Instant.now().plusSeconds(90),new CancellationToken());
            var job=prep.requestMaterials(run,owner,request,control.deadline());
            for(int n=0;n<10 && !prep.materialsReady(job.id());n++)for(var step:prep.pending(job.id()))prep.advance(job.id(),step,control.cancellation());
            var material=prep.materials(job.id(),owner,control);
            assertEquals(FeedbackCompression.RERANK_VERSION,material.version());
            assertEquals(direct?0:5,qwenCalls.get());
            assertEquals(direct?30:15,material.representativeCount());
            assertTrue(material.modelBytes()>2700,"All selected evidence survives beyond the former tail-cut limit");
            assertEquals(0,deepseekCalls.get());
            prep.startCompressedReport(job.id(),owner,null,control);
            for(int n=0;n<8 && jobs.get(job.id()).phase()!=Phase.READY;n++)service.advance(job.id());
            if(failProvider){
                assertEquals(Phase.FAILED,jobs.get(job.id()).phase());
                assertEquals(1,deepseekCalls.get(),"A one-shot confirmation cannot silently retry the provider");
                assertThrows(AgentException.class,()->prep.reportJson(job.id(),owner,control));
                return;
            }
            if(failWriter){
                assertEquals(Phase.FAILED,jobs.get(job.id()).phase());
                assertEquals(2,deepseekCalls.get());
                assertEquals(1,jobs.completedLevel(job.id(),0).size());
                assertTrue(jobs.completedLevel(job.id(),1).isEmpty());
                assertThrows(AgentException.class,()->prep.reportJson(job.id(),owner,control));return;
            }
            assertEquals(Phase.READY,jobs.get(job.id()).phase(),()->String.valueOf(jobs.get(job.id()).errorCode()));
            assertEquals(2,deepseekCalls.get());
            assertEquals(direct?30:15,sentEvidence.get());
            assertTrue(jobs.hasLevel(job.id(),1));
            assertTrue(objects.get(jobs.completedLevel(job.id(),1).get(0).result(),ResultNode.class).topics().get(0).observation().startsWith("报告正文"));
            var report=prep.reportJson(job.id(),owner,control);
            assertEquals(2L,report.method().get("confirmed_records"));
            assertEquals(28L,report.method().get("unclassified"));
            assertEquals(!direct,report.method().get("rerank_degraded"));
            assertEquals(2,report.topics().get(0).commentCount());
            assertEquals(2,report.topics().get(0).representatives().size());
            assertTrue(new String(report.html(),StandardCharsets.UTF_8).contains("本节引用"));

            if(direct){
                assertEquals("DIRECT_LLM",report.method().get("route"));
                verify(embedding,never()).embed(anyList(),any());
                return;
            }

            for(int repetition=0;repetition<2;repetition++){
                var repeated=prep.requestMaterials(UUID.randomUUID(),owner,request,control.deadline());
                for(int n=0;n<10 && !prep.materialsReady(repeated.id());n++)for(var step:prep.pending(repeated.id()))prep.advance(repeated.id(),step,control.cancellation());
                prep.startCompressedReport(repeated.id(),owner,null,control);
                for(int n=0;n<8 && jobs.get(repeated.id()).phase()!=Phase.READY;n++)service.advance(repeated.id());
                assertEquals(Phase.READY,jobs.get(repeated.id()).phase());
                assertEquals(6,qwenCalls.get(),"Only the bucket that previously received 429 should require another request");
                assertEquals(4+repetition,deepseekCalls.get(),"Confirmation reuse still requires a separate report writing step");
            }

            var recovered=prep.requestMaterials(UUID.randomUUID(),owner,request,control.deadline());
            prep.configureRerank(false,null,FeedbackReusePolicy.defaults());
            assertEquals(FeedbackCompression.RERANK_VERSION,prep.reportRulesVersion(recovered.runId()));
            assertEquals(FeedbackCompression.FIVE_VERSION,prep.reportRulesVersion(UUID.randomUUID()));
            for(int n=0;n<10 && !prep.materialsReady(recovered.id());n++)for(var step:prep.pending(recovered.id()))prep.advance(recovered.id(),step,control.cancellation());
            prep.startCompressedReport(recovered.id(),owner,null,control);
            var first=jobs.claim(recovered.id(),Instant.now(),Duration.ofMillis(200));
            assertNotNull(first);
            jobs.reserve(recovered.id(),first.id(),first.fence(),8192);
            while(Instant.now().isBefore(first.leaseUntil()))Thread.sleep(2);
            assertFalse(jobs.transitionClaim(first,Phase.WAITING_AUTH,null));
            for(int n=0;n<8 && jobs.get(recovered.id()).phase()!=Phase.READY;n++)service.advance(recovered.id());
            assertEquals(Phase.FAILED,jobs.get(recovered.id()).phase());
            assertEquals("ANALYSIS_CONFIRMATION_UNAVAILABLE",jobs.get(recovered.id()).errorCode());
            assertEquals(5,deepseekCalls.get(),"A reclaimed confirmation must not send another request");
            assertThrows(AgentException.class,()->prep.reportJson(recovered.id(),owner,control));

            var beforeReservation=prep.requestMaterials(UUID.randomUUID(),owner,request,control.deadline());
            for(int n=0;n<10 && !prep.materialsReady(beforeReservation.id());n++)
                for(var step:prep.pending(beforeReservation.id()))prep.advance(beforeReservation.id(),step,control.cancellation());
            prep.startCompressedReport(beforeReservation.id(),owner,null,control);
            var unreserved=jobs.claim(beforeReservation.id(),Instant.now().minusSeconds(1),Duration.ofMillis(1));
            assertNotNull(unreserved);
            for(int n=0;n<8 && jobs.get(beforeReservation.id()).phase()!=Phase.READY;n++)service.advance(beforeReservation.id());
            assertEquals(Phase.READY,jobs.get(beforeReservation.id()).phase());
            assertEquals(7,deepseekCalls.get(),"Lease loss before reservation must allow confirmation and report writing");
        }finally{server.stop(0);}
    }

    @Test void anUnknownRerankAttemptCannotBeReservedByANewLease(){
        var store=new MemoryFeedbackPreparationStore();
        var id=UUID.randomUUID();
        var step=FeedbackPreparationStore.Step.MATERIALS;
        store.request(id,step);
        var old=store.claim(id,step,Instant.now(),Duration.ofSeconds(30));
        assertTrue(store.reserveRerank(old,"B1",null));
        store.release(old);
        var renewed=store.claim(id,step,Instant.now().plusSeconds(4),Duration.ofSeconds(30));
        assertNotNull(renewed);
        assertFalse(store.reserveRerank(renewed,"B1",null));
        assertNull(store.rerankSlot(id,"B1").result());
        assertThrows(AgentException.class,()->store.reserveRerank(old,"B2",null));
    }
}
