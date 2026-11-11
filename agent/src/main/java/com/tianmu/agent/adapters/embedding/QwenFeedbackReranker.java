package com.tianmu.agent.adapters.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.feedback.FeedbackReranker;
import com.tianmu.agent.harness.trace.TraceUsage;
import com.tianmu.agent.runtime.control.CallControl;
import okhttp3.*;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class QwenFeedbackReranker implements FeedbackReranker {
    public static final String MODEL = "qwen3-rerank";
    private static final String NATIVE_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";
    private static final String INSTRUCTION = "按候选与参考原话讨论同一具体问题的程度排序。允许相反观点，区分仅共享关键词的反馈。";
    private final ObjectMapper json;
    private final String endpoint;
    private final String token;
    private final String model;
    private final boolean nativeProtocol;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    public QwenFeedbackReranker(String endpoint, String token, ObjectMapper json) {
        this(endpoint,token,MODEL,json);
    }
    public QwenFeedbackReranker(String endpoint, String token,String model,ObjectMapper json) {
        var uri = URI.create(endpoint);
        nativeProtocol=NATIVE_PATH.equals(uri.getPath());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !(nativeProtocol || uri.getPath().endsWith("/reranks"))
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1").contains(uri.getHost()))) {
            throw new IllegalArgumentException("Expected an explicit HTTPS reranks endpoint");
        }
        if (token == null || token.isBlank() || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("Invalid Qwen rerank token");
        }
        this.endpoint = endpoint;
        this.token = token;
        if(!Set.of(MODEL,"qwen3.7-text-rerank").contains(model))throw new IllegalArgumentException("Unsupported Qwen rerank model");
        this.model=model;
        this.json = json;
    }

    @Override public Result rank(String query, List<String> documents, CallControl control) {
        if (query == null || query.isBlank() || documents.size() < 2
                || documents.size() > CANDIDATES_PER_BUCKET || documents.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Invalid Qwen rerank inputs");
        }
        try {
            Map<String,Object> requestPayload;
            if(nativeProtocol){
                var parameters=new LinkedHashMap<String,Object>();
                parameters.put("return_documents",false);
                if(!MODEL.equals(model))parameters.put("instruct","Rank by relevance to the same specific issue. Include opposing experiences and distinguish keyword-only matches.");
                requestPayload=Map.of("model",model,"input",Map.of("query",query,"documents",documents),"parameters",parameters);
            }else requestPayload=Map.of("model",model,"query",query,"documents",documents,"instruct",INSTRUCTION,"return_documents",false);
            var payload = json.writeValueAsBytes(requestPayload);
            if (payload.length > 32768) {
                throw new AgentException("RERANK_INPUT_TOO_LARGE", "Rerank request exceeds 32 KiB");
            }
            var body = new RequestBody() {
                @Override public MediaType contentType() { return MediaType.get("application/json"); }
                @Override public long contentLength() { return payload.length; }
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(okio.BufferedSink sink) throws IOException { sink.write(payload); }
            };
            var request = new Request.Builder().url(endpoint).header("Authorization", "Bearer " + token)
                    .post(body).build();
            var call = http.newCall(request);
            control.beforeDispatch();
            long remaining = Duration.between(Instant.now(), control.deadline()).toMillis();
            if (remaining <= 0) throw new AgentException("DEADLINE_EXCEEDED", "Rerank deadline exceeded");
            call.timeout().timeout(Math.min(1500, remaining), TimeUnit.MILLISECONDS);
            TraceUsage.externalCall();
            Long tokens = null;
            try (var registration = control.cancellation().onCancel(call::cancel); var response = call.execute()) {
                control.checkpoint();
                if (!response.isSuccessful() || response.body() == null) {
                    throw new AgentException("RERANK_UNAVAILABLE", "Qwen rerank request failed");
                }
                byte[] bytes = response.body().byteStream().readNBytes(65537);
                if (bytes.length > 65536) throw invalid();
                var root = json.readTree(bytes);
                if (root == null) throw invalid();
                var usage = root.path("usage").path("total_tokens");
                if (usage.isIntegralNumber() && usage.canConvertToLong() && usage.longValue() >= 0) {
                    tokens = usage.longValue();
                }
                var results = nativeProtocol?root.path("output").path("results"):root.path("results");
                if ((!nativeProtocol && !model.equals(root.path("model").asText())) || !results.isArray()
                        || results.size() != documents.size()) throw invalid();
                var scores = new ArrayList<Double>(Collections.nCopies(documents.size(), null));
                for (var item : results) {
                    var index = item.path("index");
                    var score = item.path("relevance_score");
                    if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0
                            || index.intValue() >= scores.size() || scores.get(index.intValue()) != null
                            || !score.isNumber() || !Double.isFinite(score.doubleValue())) throw invalid();
                    scores.set(index.intValue(), score.doubleValue());
                }
                return new Result(scores, tokens);
            } finally {
                TraceUsage.record(model, tokens, 0L, null, null, null);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw invalid();
        } catch (IOException unavailable) {
            control.checkpoint();
            throw new AgentException("RERANK_UNAVAILABLE", "Qwen rerank request failed");
        }
    }

    private static AgentException invalid() {
        return new AgentException("RERANK_PROTOCOL_ERROR", "Invalid Qwen rerank response");
    }
}
