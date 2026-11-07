package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.runtime.RunService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/agent/runs/{runId}/analysis")
public final class FeedbackOperationController {
    private final RunService runs;private final IdentityPort identity;private final FeedbackAnalysisService analyses;
    public FeedbackOperationController(RunService runs,IdentityPort identity,FeedbackAnalysisService analyses){this.runs=runs;this.identity=identity;this.analyses=analyses;}
    private String token(String auth){if(auth==null || !auth.startsWith("Bearer "))throw new AgentException("UNAUTHORIZED","Login required");return auth.substring(7);}
    @GetMapping public Map<String,Object> get(@PathVariable UUID runId,@RequestHeader(value="Authorization",required=false)String auth){
        var scope=identity.forRun(token(auth),runId);runs.owned(runId,scope);var job=analyses.forRun(runId);
        if(job==null)throw new AgentException("RESULT_NOT_FOUND","No analysis associated with this run");analyses.owned(job.id(),scope);
        return Map.of("job_id",job.id(),"phase",job.phase(),"completed_work",job.completedWork(),"model_calls",job.modelCalls(),"charged_tokens",job.chargedTokens(),"deadline",job.deadline(),"error_code",Objects.toString(job.errorCode(),""));
    }
    @PostMapping("/resume") public Map<String,Object> resume(@PathVariable UUID runId,@RequestHeader(value="Authorization",required=false)String auth){
        var scope=identity.forRun(token(auth),runId);var run=runs.owned(runId,scope);var job=analyses.forRun(runId);
        if(run.snapshot().status()!=com.tianmu.agent.core.AgentTypes.Status.WAITING_OPERATION || job==null || !job.id().equals(run.snapshot().operation().id()))throw new AgentException("INVALID_OPERATION_STATE","No waiting analysis");
        identity.renew(run.owner,scope);if(job.phase()==com.tianmu.agent.core.FeedbackAnalysis.Phase.WAITING_AUTH)analyses.resume(job.id(),scope);
        return Map.of("status","RESUMED","job_id",job.id());
    }
}
