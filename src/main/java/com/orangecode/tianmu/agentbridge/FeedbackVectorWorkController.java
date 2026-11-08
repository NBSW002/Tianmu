package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@ConditionalOnProperty(name="feedback.vector-work.enabled",havingValue="true")
@RequestMapping("/internal/agent/vector-work")
public final class FeedbackVectorWorkController {
    private final FeedbackVectorWork work;private final BusinessFeedbackSnapshot snapshots;private final byte[] secret;
    public FeedbackVectorWorkController(FeedbackVectorWork work,BusinessFeedbackSnapshot snapshots,@Value("${feedback.vector-work.token-file}")String file)throws java.io.IOException{
        this.work=work;this.snapshots=snapshots;secret=Files.readString(Path.of(file)).strip().getBytes(StandardCharsets.UTF_8);
        if(secret.length<32 || secret.length>256)throw new IllegalArgumentException("Invalid vector work service secret");
    }
    private void auth(String token){if(token==null || !MessageDigest.isEqual(secret,token.getBytes(StandardCharsets.UTF_8)))throw new BridgeException("UNAUTHORIZED",401);}
    @PostMapping("/claim") public ResponseEntity<FeedbackVectorWork.Lease> claim(@RequestHeader(value="X-Vector-Work-Token",required=false)String token){auth(token);var lease=work.claim();return lease==null?ResponseEntity.noContent().build():ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lease);}
    @PostMapping("/snapshot") public ResponseEntity<StreamingResponseBody> snapshot(@RequestHeader(value="X-Vector-Work-Token",required=false)String token,@RequestBody FeedbackVectorWork.Lease lease){
        auth(token);work.require(lease);if(lease.ownerId()==null)throw new BridgeException("VIDEO_UNAVAILABLE",404);
        var export=snapshots.export(new BusinessAuthentication.Principal(lease.tenantId(),lease.ownerId()),new BusinessFeedbackSnapshot.Request(lease.videoId(),LocalDateTime.of(1970,1,1,0,0),LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).withNano(0),Set.of("COMMENT","DANMAKU")));
        java.util.concurrent.CompletableFuture.runAsync(export::close,java.util.concurrent.CompletableFuture.delayedExecutor(2,java.util.concurrent.TimeUnit.MINUTES));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/x-ndjson")).body(out->{try(export){Files.copy(export.file(),out);}});
    }
    public record Completion(FeedbackVectorWork.Lease lease,boolean success){}
    @PostMapping("/complete") public void complete(@RequestHeader(value="X-Vector-Work-Token",required=false)String token,@RequestBody Completion completion){auth(token);work.finish(completion.lease(),completion.success());}
    @ExceptionHandler(BridgeException.class) ResponseEntity<Map<String,String>> failure(BridgeException e){return ResponseEntity.status(e.status()).body(Map.of("code",e.code()));}
}
