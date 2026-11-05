package com.tianmu.agent.adapters.embedding;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QwenFeedbackEmbeddingTest {
    HttpServer server;QwenFeedbackEmbedding embedding;final ObjectMapper json=new ObjectMapper();
    volatile String response;volatile int status=200;final List<JsonNode> requests=new ArrayList<>();
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/embeddings",exchange->{try{
            requests.add(json.readTree(exchange.getRequestBody()));byte[] body=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
        }finally{exchange.close();}});server.start();
        embedding=new QwenFeedbackEmbedding("http://127.0.0.1:"+server.getAddress().getPort()+"/embeddings","test-only-token","qwen3.7-text-embedding-flash","test-20261105",256,json);
    }
    @AfterEach void close(){server.stop(0);}
    CallControl control(){return new CallControl(Instant.now().plusSeconds(3),new CancellationToken());}
    Map<String,Object> row(int index,float value){var vector=new ArrayList<>(Collections.nCopies(256,0f));vector.set(0,value);return Map.of("index",index,"embedding",vector);}
    void reply(List<?> data)throws Exception {response=json.writeValueAsString(Map.of("model","qwen3.7-text-embedding-flash","data",data,"usage",Map.of("total_tokens",10)));}
    @Test void responseIndexesRestoreInputOrder()throws Exception {
        reply(List.of(row(1,2),row(0,1)));var result=embedding.embed(List.of("没声音","听不到"),control());
        assertEquals(1f,result.get(0).get(0));assertEquals(2f,result.get(1).get(0));
        assertEquals(256,requests.get(0).path("dimensions").asInt());assertEquals("float",requests.get(0).path("encoding_format").asText());
    }
    @Test void duplicatesMissingAndWrongDimensionsFailClosed()throws Exception {
        for(var rows:List.of(List.of(row(0,1),row(0,1)),List.of(row(0,1)),List.of(row(0,1),Map.of("index",1,"embedding",List.of(1f))))){
            reply(rows);assertEquals("EMBEDDING_PROTOCOL_ERROR",assertThrows(AgentException.class,()->embedding.embed(List.of("a","b"),control())).code());
        }
    }
    @Test void rateLimitAndCredentialErrorsDoNotLeakProviderBody(){
        response="test-only-token";
        for(int code:List.of(429,401,403,302)){
            status=code;var failure=assertThrows(AgentException.class,()->embedding.embed(List.of("x"),control()));
            assertFalse(failure.getMessage().contains("test-only-token"));
            assertEquals(code==429?"EMBEDDING_RATE_LIMITED":code==401 || code==403?"EMBEDDING_AUTH_FAILED":"EMBEDDING_UNAVAILABLE",failure.code());
        }
        assertEquals(4,requests.size());
    }
    @Test void inputBudgetsAndCancellationPrecedeDispatch(){
        assertThrows(IllegalArgumentException.class,()->embedding.embed(Collections.nCopies(21,"x"),control()));
        assertEquals("EMBEDDING_INPUT_TOO_LARGE",assertThrows(AgentException.class,()->embedding.embed(List.of("x".repeat(65536)),control())).code());
        var c=control();c.cancellation().cancel(com.tianmu.agent.core.StopReason.USER_CANCELLED);
        assertThrows(AgentException.class,()->embedding.embed(List.of("x"),c));assertTrue(requests.isEmpty());
    }
}
