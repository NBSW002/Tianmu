package com.tianmu.agent.api;

import com.tianmu.agent.application.TraceQueryService;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceEnvelope;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/agent/traces")
public final class TraceQueryController {
    private final TraceQueryService traces;
    public TraceQueryController(TraceQueryService traces) { this.traces = traces; }
    @GetMapping("/{traceId}")
    public ResponseEntity<List<TraceEnvelope>> get(@PathVariable String traceId,
            @RequestHeader(value="Authorization", required=false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "Bearer authentication required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(traces.find(traceId, auth.substring(7)));
    }
}
