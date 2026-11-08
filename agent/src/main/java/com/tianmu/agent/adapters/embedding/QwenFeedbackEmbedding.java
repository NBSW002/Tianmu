package com.tianmu.agent.adapters.embedding;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.feedback.FeedbackEmbedding;
import com.tianmu.agent.runtime.control.CallControl;
import okhttp3.*;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Qwen compatible embeddings protocol; explicit endpoint, no redirects/retries or payload logging. */
public final class QwenFeedbackEmbedding implements FeedbackEmbedding {
    private final String endpoint,token,model,revision;private final int dimension;private final ObjectMapper json;private final OkHttpClient http;
    public QwenFeedbackEmbedding(String endpoint,String token,String model,String revision,int dimension,ObjectMapper json){
        URI uri;try{uri=URI.create(endpoint);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid embedding endpoint");}
        if(uri.getHost()==null || !(uri.getScheme().equals("https") || uri.getScheme().equals("http") && Set.of("127.0.0.1","localhost").contains(uri.getHost()))
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().endsWith("/embeddings"))throw new IllegalArgumentException("Embedding endpoint must be an explicit HTTPS embeddings URL");
        boolean supported="qwen3.7-text-embedding-flash".equals(model) && Set.of(256,512,768,1024).contains(dimension)
                || "qwen3.7-text-embedding".equals(model) && Set.of(256,512,768,1024,1536,2048,2560).contains(dimension);
        if(!supported || revision==null || !revision.matches("[A-Za-z0-9_.:-]{1,100}"))throw new IllegalArgumentException("Invalid Qwen embedding profile");
        if(token==null || token.isBlank() || token.length()>8192 || token.chars().anyMatch(c->c<33 || c>126))throw new IllegalArgumentException("Invalid embedding token");
        this.endpoint=endpoint;this.token=token;this.model=model;this.revision=revision;this.dimension=dimension;this.json=json.copy();
        http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(3,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build();
    }
    public String version(){return model+":"+revision+":dim"+dimension+":framed-text-reply-v1";}
    public int dimension(){return dimension;}
    public List<List<Float>> embed(List<String> inputs,CallControl control){
        check(control);if(inputs.isEmpty() || inputs.size()>20 || inputs.stream().anyMatch(s->s==null || s.isBlank()))throw new IllegalArgumentException("Embedding batch must contain 1..20 texts");
        try {
            byte[] payload=json.writeValueAsBytes(Map.of("model",model,"input",inputs,"dimensions",dimension,"encoding_format","float"));
            if(payload.length>65536)throw new AgentException("EMBEDDING_INPUT_TOO_LARGE","Embedding request exceeds 64 KiB budget");
            var call=http.newCall(new Request.Builder().url(endpoint).header("Authorization","Bearer "+token).post(RequestBody.create(MediaType.get("application/json"),payload)).build());
            call.timeout().timeout(Math.max(1,Math.min(30000,Duration.between(Instant.now(),control.deadline()).toMillis())),TimeUnit.MILLISECONDS);
            control.beforeDispatch();
            boolean recorded=false;com.tianmu.agent.harness.trace.TraceUsage.externalCall();
            try(var registration=control.cancellation().onCancel(call::cancel);var response=call.execute()){
                check(control);
                if(response.code()==429)throw new AgentException("EMBEDDING_RATE_LIMITED","Embedding provider rate limited");
                if(response.code()==401 || response.code()==403)throw new AgentException("EMBEDDING_AUTH_FAILED","Embedding authentication failed");
                if(!response.isSuccessful() || response.body()==null)throw new AgentException("EMBEDDING_UNAVAILABLE","Embedding provider unavailable");
                byte[] bytes=response.body().byteStream().readNBytes(4194305);if(bytes.length>4194304)throw protocol();
                var root=json.readTree(bytes);if(root==null || !root.path("data").isArray() || root.path("data").size()!=inputs.size() || !model.equals(root.path("model").asText()))throw protocol();
                var ordered=new ArrayList<List<Float>>(Collections.nCopies(inputs.size(),null));
                for(var item:root.path("data")){
                    var position=item.path("index");var vector=item.path("embedding");
                    if(!position.isIntegralNumber() || !position.canConvertToInt() || position.intValue()<0 || position.intValue()>=inputs.size() || ordered.get(position.intValue())!=null || !vector.isArray() || vector.size()!=dimension)throw protocol();
                    var values=new ArrayList<Float>();double norm=0;
                    for(var n:vector){float value=n.floatValue();if(!n.isNumber() || !Float.isFinite(value))throw protocol();values.add(value);norm+=(double)value*value;}
                    if(norm==0)throw protocol();ordered.set(position.intValue(),List.copyOf(values));
                }
                var usage=root.path("usage").path("total_tokens");Long tokens=usage.isIntegralNumber() && usage.canConvertToLong() && usage.longValue()>=0?usage.longValue():null;
                com.tianmu.agent.harness.trace.TraceUsage.record(model,tokens,0L,null,null,null);
                recorded=true;
                check(control);return List.copyOf(ordered);
            }finally{if(!recorded)com.tianmu.agent.harness.trace.TraceUsage.record(model,null,null,null,null,null);}
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw protocol();}
        catch(IOException unavailable){check(control);throw new AgentException("EMBEDDING_UNAVAILABLE","Embedding provider unavailable");}
    }
    private static AgentException protocol(){return new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid embedding response");}
    private static void check(CallControl c){c.checkpoint();if(!Instant.now().isBefore(c.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Embedding deadline exceeded");}
}
