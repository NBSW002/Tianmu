package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.file.Files;

@RestController
@ConditionalOnProperty(name="agent.bridge.enabled",havingValue="true")
@RequestMapping("/internal/agent/feedback-snapshot")
public final class FeedbackSnapshotController {
    private final BusinessAuthentication auth;private final BusinessFeedbackSnapshot snapshots;
    public FeedbackSnapshotController(BusinessAuthentication auth,BusinessFeedbackSnapshot snapshots){this.auth=auth;this.snapshots=snapshots;}
    @PostMapping
    public ResponseEntity<StreamingResponseBody> export(@RequestHeader(value="Authorization",required=false)String authorization,@RequestBody BusinessFeedbackSnapshot.Request request){
        var exported=snapshots.export(auth.authenticate(authorization),request);
        // Also covers MVC rejecting the async response before the streaming callback starts.
        java.util.concurrent.CompletableFuture.runAsync(exported::close,java.util.concurrent.CompletableFuture.delayedExecutor(2,java.util.concurrent.TimeUnit.MINUTES));
        StreamingResponseBody body=out->{try(exported){Files.copy(exported.file(),out);}};
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/x-ndjson")).contentLength(exported.bytes()).body(body);
    }
    @ExceptionHandler(BridgeException.class)
    public ResponseEntity<java.util.Map<String,String>> failure(BridgeException e){return ResponseEntity.status(e.status()).body(java.util.Map.of("code",e.code()));}
}
