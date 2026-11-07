package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceDetails;
import com.tianmu.agent.runtime.store.RunRepository;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent/runs/{runId}/trace-payloads")
public final class TraceDetailController {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<com.tianmu.agent.runtime.spi.TraceSourceAuthority> sourceAuthorities;
    private final IdentityPort identity;
    private final RunRepository runs;
    private final TraceDetails details;
    public TraceDetailController(IdentityPort identity, RunRepository runs, TraceDetails details) { this.identity = identity; this.runs = runs; this.details = details; }
    @GetMapping("/{spanId}/{direction}/{bytes}/{sha}")
    public ResponseEntity<byte[]> get(@PathVariable UUID runId, @PathVariable String spanId, @PathVariable String direction,
            @PathVariable long bytes, @PathVariable String sha, @RequestHeader(value="Authorization", required=false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "Bearer authentication required");
        var caller=identity.forRun(auth.substring(7), runId);
        var run=runs.load(runId, caller);
        byte[] payload=details.read(runId,spanId,direction,bytes,sha);
        if(details.requiresSourceGuard(payload)) {
            var authority=sourceAuthorities==null?null:sourceAuthorities.getIfAvailable();
            if(authority==null)throw new AgentException("MEMORY_SOURCE_UNAVAILABLE","Trace source authorization is unavailable");
            authority.verify(run,caller);
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(payload);
    }
}
