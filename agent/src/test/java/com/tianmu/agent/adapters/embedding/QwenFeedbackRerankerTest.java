package com.tianmu.agent.adapters.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.feedback.FeedbackReranker;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class QwenFeedbackRerankerTest {
    @Test void supportsTheQwenCloudNativeTextRerankProtocol() throws Exception {
        var json=new ObjectMapper();
        var request=new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var path="/api/v1/services/rerank/text-rerank/text-rerank";
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(path,exchange->{
            try(exchange){
                request.set(json.readTree(exchange.getRequestBody()));
                var body="""
                        {"output":{"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}]},
                         "usage":{"total_tokens":38}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try{
            var adapter=new QwenFeedbackReranker("http://127.0.0.1:"+server.getAddress().getPort()+path,
                    "test","qwen3.7-text-rerank",json);
            var control=new CallControl(Instant.now().plusSeconds(10),new CancellationToken());
            var result=adapter.rank("画面卡顿",List.of("背景音乐","播放掉帧"),control);
            assertEquals(List.of(1,0),FeedbackReranker.topEvidence(2,result));
            assertEquals(38L,result.inputTokens());
            assertEquals("qwen3.7-text-rerank",request.get().path("model").asText());
            assertEquals("画面卡顿",request.get().path("input").path("query").asText());
            assertTrue(request.get().path("parameters").path("instruct").isTextual());
        }finally{server.stop(0);}
    }

    @Test void doesNotResendARequestWhenProviderAsksForImmediateRetry() throws Exception {
        var posts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reranks", exchange -> {
            try (exchange) {
                posts.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(503, -1);
            }
        });
        server.start();
        try {
            var adapter = new QwenFeedbackReranker("http://127.0.0.1:" + server.getAddress().getPort() + "/reranks",
                    "test", new ObjectMapper());
            var control = new CallControl(Instant.now().plusSeconds(10), new CancellationToken());
            assertEquals("RERANK_UNAVAILABLE",
                    assertThrows(AgentException.class, () -> adapter.rank("anchor", List.of("a", "b"), control)).code());
            assertEquals(1, posts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test void mapsUnorderedProviderIndicesAndRejectsDuplicateIndices() throws Exception {
        var json = new ObjectMapper();
        var request = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var response = new AtomicReference<>("""
                {"model":"qwen3-rerank","usage":{"total_tokens":23},"results":[
                {"index":3,"relevance_score":0.8},{"index":0,"relevance_score":0.1},
                {"index":2,"relevance_score":0.8},{"index":1,"relevance_score":0.9}]}
                """);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reranks", exchange -> {
            try (exchange) {
                request.set(json.readTree(exchange.getRequestBody()));
                byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var adapter = new QwenFeedbackReranker("http://127.0.0.1:" + server.getAddress().getPort() + "/reranks", "test", json);
            var control = new CallControl(Instant.now().plusSeconds(10), new CancellationToken());
            var documents = List.of("候选甲", "候选乙", "候选丙", "候选丁");
            var ranked = adapter.rank("中心原话", documents, control);
            assertEquals(List.of(1, 2, 3, 0), FeedbackReranker.topEvidence(4, ranked));
            assertEquals(List.of(0, 1, 2, 3), FeedbackReranker.topEvidence(4, null));
            assertEquals(23L, ranked.inputTokens());
            assertEquals("中心原话", request.get().path("query").asText());
            assertEquals(json.valueToTree(documents), request.get().path("documents"));
            response.set(response.get().replace("\"index\":3", "\"index\":2"));
            assertEquals("RERANK_PROTOCOL_ERROR",
                    assertThrows(AgentException.class, () -> adapter.rank("中心原话", documents, control)).code());
        } finally {
            server.stop(0);
        }
    }
}
