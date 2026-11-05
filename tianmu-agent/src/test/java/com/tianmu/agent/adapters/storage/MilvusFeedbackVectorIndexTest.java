package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.feedback.FeedbackVectorIndex.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MilvusFeedbackVectorIndexTest {
    HttpServer server; ExecutorService threads; MilvusFeedbackVectorIndex index;
    final ObjectMapper json=new ObjectMapper();
    final Namespace ns=new Namespace("tenant","owner",1001,Source.COMMENT,"embedding-model@revision:template-v1:dim3");
    final Reference ref=new Reference("C:1","a".repeat(64));
    final List<Float> vector=List.of(1f,0f,0f);
    final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    final List<String> paths=new CopyOnWriteArrayList<>();
    volatile int status=200; volatile String reply="{\"code\":0,\"data\":{\"upsertCount\":1}}";
    volatile long delay; final CountDownLatch entered=new CountDownLatch(1);
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        threads=Executors.newCachedThreadPool();server.setExecutor(threads);
        server.createContext("/v2/vectordb/entities/",e->{
            try {
                requests.add(json.readTree(e.getRequestBody()));paths.add(e.getRequestURI().getPath());entered.countDown();
                assertEquals("Bearer test-only-token",e.getRequestHeaders().getFirst("Authorization"));
                if(delay>0)Thread.sleep(delay);
                byte[] body=reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally{e.close();}
        });
        server.start();index=adapter(Duration.ofSeconds(2));
    }
    MilvusFeedbackVectorIndex adapter(Duration timeout){return new MilvusFeedbackVectorIndex("http://127.0.0.1:"+server.getAddress().getPort(),"test-only-token","default","feedback_test",3,timeout,json);}
    @AfterEach void stop(){server.stop(0);threads.shutdownNow();}
    CallControl control(){return new CallControl(Instant.now().plusSeconds(5),new CancellationToken());}
    String code(Runnable work){return assertThrows(AgentException.class,work::run).code();}
    Map<String,Object> hit(){return new LinkedHashMap<>(Map.of("id",ref.key(ns),"scope_key",ns.key(),"record_id",ref.recordId(),"content_version",ref.contentVersion(),"distance",0.9));}
    void searchReply(List<?> hits) throws Exception{reply=json.writeValueAsString(Map.of("code",0,"data",hits));}

    @Test void idempotentVersionKeysAndNoRawTextInUpsert(){
        var entries=List.of(new Entry(ref,vector));index.upsert(ns,entries,control());index.upsert(ns,entries,control());
        assertEquals(requests.get(0),requests.get(1));
        var row=requests.get(0).path("data").get(0);
        assertEquals(ref.key(ns),row.path("id").asText());assertEquals(ns.key(),row.path("scope_key").asText());
        assertEquals(Set.of("id","scope_key","record_id","content_version","vector"),json.convertValue(row,Map.class).keySet());
        assertEquals("/v2/vectordb/entities/upsert",paths.get(0));
    }
    @Test void everyOwnershipAndVersionDimensionChangesNamespace(){
        for(var other:List.of(new Namespace("other","owner",1001,Source.COMMENT,ns.embeddingVersion()),
                new Namespace("tenant","other",1001,Source.COMMENT,ns.embeddingVersion()),
                new Namespace("tenant","owner",1002,Source.COMMENT,ns.embeddingVersion()),
                new Namespace("tenant","owner",1001,Source.DANMAKU,ns.embeddingVersion()),
                new Namespace("tenant","owner",1001,Source.COMMENT,"next-version")))assertNotEquals(ns.key(),other.key());
        assertNotEquals(ref.key(ns),new Reference("C:1","b".repeat(64)).key(ns));
    }
    @Test void searchEnforcesScopeStrongConsistencyAndCosine() throws Exception {
        searchReply(List.of(hit()));var result=index.search(ns,vector,12,control());
        assertEquals(ref,result.get(0).reference());assertEquals(0.9,result.get(0).similarity());
        var body=requests.get(0);assertEquals("scope_key == \""+ns.key()+"\"",body.path("filter").asText());
        assertEquals("Strong",body.path("consistencyLevel").asText());
        assertEquals("COSINE",body.path("searchParams").path("metricType").asText());assertEquals(12,body.path("limit").asInt());
    }
    @Test void forgedScopeIdentityAndScoresAreRejected() throws Exception {
        for(var field:List.of("scope_key","id","content_version","record_id","distance")){
            var hit=hit();hit.put(field,field.equals("distance")?99:"invalid");searchReply(List.of(hit));
            assertEquals("VECTOR_PROTOCOL_ERROR",code(()->index.search(ns,vector,12,control())));
        }
    }
    @Test void emptySearchIsDifferentFromErrors() throws Exception {
        searchReply(List.of());assertTrue(index.search(ns,vector,12,control()).isEmpty());
        reply="{\"code\":123,\"message\":\"test-only-token\",\"data\":[]}";
        var error=assertThrows(AgentException.class,()->index.search(ns,vector,12,control()));
        assertEquals("VECTOR_OPERATION_FAILED",error.code());assertFalse(error.getMessage().contains("test-only-token"));
        reply="{\"data\":[]}";assertEquals("VECTOR_PROTOCOL_ERROR",code(()->index.search(ns,vector,12,control())));
        reply="not-json";assertEquals("VECTOR_PROTOCOL_ERROR",code(()->index.search(ns,vector,12,control())));
    }
    @Test void boundedVersionDeleteCannotDeleteWholeNamespace(){
        reply="{\"code\":0,\"data\":{\"deleteCount\":1}}";
        index.delete(ns,List.of(ref),control());
        assertEquals("scope_key == \""+ns.key()+"\" and id in [\""+ref.key(ns)+"\"]",requests.get(0).path("filter").asText());
        assertThrows(IllegalArgumentException.class,()->index.delete(ns,List.of(),control()));
        assertThrows(IllegalArgumentException.class,()->index.delete(ns,List.of(new Reference("D:1","a".repeat(64))),control()));
        assertEquals(1,requests.size());
    }
    @Test void dimensionNonfiniteZeroAndLimitValidationPrecedeNetwork(){
        for(var v:List.of(List.of(1f),List.of(Float.NaN,0f,0f),List.of(0f,0f,0f)))
            assertThrows(IllegalArgumentException.class,()->index.search(ns,v,10,control()));
        assertThrows(IllegalArgumentException.class,()->index.search(ns,vector,257,control()));
        assertThrows(IllegalArgumentException.class,()->index.upsert(ns,List.of(new Entry(ref,vector),new Entry(ref,vector)),control()));
        assertTrue(requests.isEmpty());
    }
    @Test void mutationCountMustMatch(){
        reply="{\"code\":0,\"data\":{\"upsertCount\":0}}";
        assertEquals("VECTOR_PROTOCOL_ERROR",code(()->index.upsert(ns,List.of(new Entry(ref,vector)),control())));
    }
    @Test void rateLimitAuthenticationAndRedirectAreNotSilentlyRetried(){
        for(int code:List.of(429,401,302)){
            status=code;String expected=code==429?"VECTOR_RATE_LIMITED":code==401?"VECTOR_AUTH_FAILED":"VECTOR_STORE_UNAVAILABLE";
            assertEquals(expected,code(()->index.search(ns,vector,1,control())));
        }
        assertEquals(3,requests.size());
    }
    @Test void responseSizeAndOperationTimeoutAreBounded(){
        reply="x".repeat(4*1024*1024+1);
        assertEquals("VECTOR_RESPONSE_TOO_LARGE",code(()->index.search(ns,vector,1,control())));
        delay=800;index=adapter(Duration.ofMillis(100));
        assertEquals("VECTOR_STORE_UNAVAILABLE",code(()->index.search(ns,vector,1,control())));
    }
    @Test void cancellationStopsInFlightRequest() throws Exception {
        delay=2000;var c=control();var executor=Executors.newSingleThreadExecutor();
        try {
            var pending=executor.submit(()->code(()->index.search(ns,vector,1,c)));
            assertTrue(entered.await(2,TimeUnit.SECONDS));c.cancellation().cancel(StopReason.USER_CANCELLED);
            assertEquals("CANCELLED",pending.get(2,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();}
    }
    @Test void expiredDeadlineDoesNotDispatch(){
        assertEquals("DEADLINE_EXCEEDED",code(()->index.search(ns,vector,1,new CallControl(Instant.now().minusSeconds(1),new CancellationToken()))));
        assertTrue(requests.isEmpty());
    }
    @Test void cachedVectorsAreReadByExactVersionAndCannotCrossNamespaces() throws Exception {
        var row=hit();row.remove("distance");row.put("vector",vector);searchReply(List.of(row));
        assertEquals(vector,index.fetch(ns,List.of(ref),control()).get(ref));
        assertEquals(List.of(ref.key(ns)),json.convertValue(requests.get(0).path("id"),List.class));
        assertEquals("Strong",requests.get(0).path("consistencyLevel").asText());
        row.put("scope_key","forged");searchReply(List.of(row));
        assertEquals("VECTOR_PROTOCOL_ERROR",code(()->index.fetch(ns,List.of(ref),control())));
    }
}
