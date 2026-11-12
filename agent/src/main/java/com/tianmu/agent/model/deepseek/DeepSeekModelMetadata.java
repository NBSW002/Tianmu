package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.model.ModelMetadataPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;

/** Bounded discovery on the configured provider only; failures never invent model limits. */
@Component @Profile("deepseek")
public final class DeepSeekModelMetadata implements ModelMetadataPort {
    private final DeepSeekProperties config;
    private final ObjectMapper json;
    private final HttpClient http;
    private volatile List<Metadata> cached = List.of();
    private volatile long expiresAt;
    public DeepSeekModelMetadata(DeepSeekProperties config, ObjectMapper json) {
        this.config=config; this.json=json;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public synchronized List<Metadata> list() {
        if (expiresAt!=0 && System.nanoTime()-expiresAt<0) return cached;
        String host=config.baseUrl().getHost();
        // Compatible providers may reuse model IDs; do not attach their limits to DeepSeek traces.
        String provider=host.equals("api.deepseek.com") || host.endsWith(".deepseek.com") ? "deepseek" : "compatible";
        try {
            String base=config.baseUrl().toString().replaceAll("/+$", "");
            var request=HttpRequest.newBuilder(URI.create(base+"/models")).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer "+config.apiKey()).GET().build();
            var future=http.sendAsync(request,info -> new LimitedBody());
            HttpResponse<byte[]> response;
            try {response=future.get(3,TimeUnit.SECONDS);}
            catch(Exception failure){future.cancel(true);throw failure;}
            {
                if(response.statusCode()!=200)throw new IllegalStateException("Metadata unavailable");
                byte[] bytes=response.body();
                JsonNode data=json.readTree(bytes).path("data");
                if(!data.isArray() || data.size()>256)throw new IllegalStateException("Invalid model metadata");
                var result=new ArrayList<Metadata>();var ids=new HashSet<String>();
                long fetched=System.currentTimeMillis();
                for(var model:data) {
                    String id=model.path("id").asText("");
                    if(!id.matches("[a-zA-Z0-9_.:/-]{1,100}") || !ids.add(id))continue;
                    result.add(new Metadata(provider,id,limit(model.get("context_window")),limit(model.get("max_output_tokens")),fetched));
                }
                cached=List.copyOf(result);expiresAt=System.nanoTime()+Duration.ofMinutes(10).toNanos();return cached;
            }
        } catch(Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            cached=List.of();expiresAt=System.nanoTime()+Duration.ofSeconds(30).toNanos();return cached;
        }
    }
    private static Long limit(JsonNode value) {
        return value!=null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue()>0 ? value.longValue() : null;
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>131072-bytes.size()){
                    subscription.cancel();result.completeExceptionally(new IllegalStateException("Metadata too large"));return;
                }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
