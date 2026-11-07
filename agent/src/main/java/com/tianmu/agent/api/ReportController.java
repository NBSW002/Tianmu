package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.store.ReportStore;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent/runs/{runId}/reports")
public final class ReportController {
    private final IdentityPort identity; private final ReportStore reports;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.runtime.RunService runs;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.feedback.FeedbackPreparationService preparation;
    public ReportController(IdentityPort identity, ReportStore reports) { this.identity = identity; this.reports = reports; }
    @GetMapping("/{artifactId}")
    public ResponseEntity<byte[]> report(@PathVariable UUID runId, @PathVariable String artifactId,
            @RequestHeader(value="Authorization", required=false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "Bearer authentication required");
        var owner = identity.forRun(auth.substring(7), runId);
        byte[] body = reports.report(runId, owner, artifactId);
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'self'")
                .header("X-Content-Type-Options", "nosniff").body(body);
    }
    @GetMapping("/{artifactId}/json")
    public ResponseEntity<com.tianmu.agent.feedback.FeedbackReportJson> json(@PathVariable UUID runId,@PathVariable String artifactId,@RequestHeader(value="Authorization",required=false) String auth){
        if(auth==null || !auth.startsWith("Bearer "))throw new AgentException("UNAUTHORIZED","Bearer authentication required");
        var owner=identity.forRun(auth.substring(7),runId);reports.report(runId,owner,artifactId);
        var provenance=reports.describeReport(runId,owner,artifactId);if(runs==null || preparation==null)throw new AgentException("RESULT_NOT_FOUND","Report JSON unavailable");
        var run=runs.owned(runId,owner);var evidence=run.execution().evidence().get(provenance.evidenceId());if(evidence==null || evidence.feedback()==null)throw new AgentException("RESULT_NOT_FOUND","Report JSON unavailable");
        var value=preparation.storedReportJson(evidence.feedback().jobId(),owner);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(value);
    }
}
