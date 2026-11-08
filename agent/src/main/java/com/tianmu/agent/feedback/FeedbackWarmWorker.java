package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.Unit;
import com.tianmu.agent.runtime.control.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.feedback.warm.enabled",havingValue="true")
@org.springframework.boot.context.properties.EnableConfigurationProperties(FeedbackWarmProperties.class)
public class FeedbackWarmWorker {
    private final FeedbackCompression compression;private final ObjectMapper json;private final String base,token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final FeedbackWarmProperties policy;
    public FeedbackWarmWorker(FeedbackCompression compression,ObjectMapper json,FeedbackWarmProperties policy)throws java.io.IOException{
        String base=policy.businessUrl(),file=policy.tokenFile();this.policy=policy;
        this.compression=compression;this.json=json.copy().findAndRegisterModules();this.base=base.replaceAll("/+$","");
        var uri=URI.create(base);if(uri.getHost()==null || !Set.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException("Invalid warm business URL");
        token=Files.readString(Path.of(file)).strip();if(token.length()<32 || token.length()>256)throw new IllegalArgumentException("Invalid warm service secret");
    }
    @Bean ThreadPoolTaskScheduler feedbackWarmScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("feedback-warm-");return scheduler;}
    private HttpRequest request(String path,Object data)throws Exception{return HttpRequest.newBuilder(URI.create(base+"/internal/agent/vector-work/"+path)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json").header("X-Vector-Work-Token",token).POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(data))).build();}
    @Scheduled(scheduler="feedbackWarmScheduler",fixedDelayString="${agent.feedback.warm.poll-millis:5000}")
    public void poll(){
        JsonNode lease=null;boolean success=false;
        try{
            var claim=http.send(request("claim",Map.of()),HttpResponse.BodyHandlers.ofInputStream());
            try(var in=claim.body()){if(claim.statusCode()==204)return;if(claim.statusCode()!=200)throw new IllegalStateException("Warm claim rejected");byte[] bytes=in.readNBytes(8193);if(bytes.length>8192)throw new IllegalStateException("Invalid warm lease");lease=json.readTree(bytes);}
            if(lease.path("ownerId").isNull()){success=true;return;}
            long video=lease.path("videoId").longValue();var owner=new AccessScope(lease.path("tenantId").asText(),lease.path("ownerId").asText(),Set.of(video));
            var control=new CallControl(Instant.now().plusSeconds(policy.timeoutSeconds()),new CancellationToken());int[] calls={0};
            var snapshot=http.send(request("snapshot",lease),HttpResponse.BodyHandlers.ofInputStream());
            var finished=new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.CompletableFuture.runAsync(()->{if(!finished.get())try{snapshot.body().close();}catch(java.io.IOException ignored){}},java.util.concurrent.CompletableFuture.delayedExecutor(policy.timeoutSeconds(),java.util.concurrent.TimeUnit.SECONDS));
            try(var in=snapshot.body()){
                if(snapshot.statusCode()!=200)throw new IllegalStateException("Warm snapshot rejected");
                var batch=new ArrayList<Unit>();long bytes=0;int records=0;boolean receipt=false;
                while(true){control.checkpoint();if(!Instant.now().isBefore(control.deadline()))throw new IllegalStateException("Warm deadline exceeded");
                    var line=new java.io.ByteArrayOutputStream();int next;
                    while((next=in.read())!=-1 && next!='\n'){if(++bytes>67108864 || line.size()>=262144)throw new IllegalStateException("Warm snapshot budget exceeded");line.write(next);}
                    if(line.size()==0 && next==-1)break;if(receipt)throw new IllegalStateException("Data after receipt");
                    var row=json.readTree(line.toByteArray());if(row.has("receipt")){receipt=true;continue;}
                    if(!row.has("unit") || ++records>20000)throw new IllegalStateException("Invalid warm unit");batch.add(json.treeToValue(row.get("unit"),Unit.class));
                    if(batch.size()==16){warm(owner,video,batch,control,calls);batch.clear();}
                }
                if(!receipt)throw new IllegalStateException("Missing warm receipt");if(!batch.isEmpty())warm(owner,video,batch,control,calls);
                success=true;
            }finally{finished.set(true);}
        }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();org.slf4j.LoggerFactory.getLogger(getClass()).warn("feedback_warm_failed type={}",failure.getClass().getSimpleName());}
        finally{if(lease!=null)try{var result=http.send(request("complete",Map.of("lease",lease,"success",success)),HttpResponse.BodyHandlers.discarding());if(result.statusCode()!=200)throw new IllegalStateException();}catch(Exception ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("feedback_warm_completion_unconfirmed");}}
    }
    private void warm(AccessScope owner,long video,List<Unit> rows,CallControl control,int[] calls){compression.warm(owner,video,rows,control,()->{if(++calls[0]>policy.maxEmbeddingCalls())throw new IllegalStateException("Warm embedding budget exceeded");});}
}
