package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.runtime.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Scheduler never blocks on a model. A job is dispatched only after its run's wait is durably visible. */
@Component
public final class FeedbackOperationCoordinator {
    private final FeedbackAnalysisService service;private final RunService runs;private final FeedbackPolicy policy;
    private final com.tianmu.agent.harness.RuntimeHarness harness;
    private final TaskAssembler assembler;
    private final ThreadPoolExecutor workers;
    private final Set<UUID> submitted=ConcurrentHashMap.newKeySet();private final Map<UUID,Long> progress=new ConcurrentHashMap<>();
    public FeedbackOperationCoordinator(FeedbackAnalysisService service,RunService runs,FeedbackPolicy policy,com.tianmu.agent.harness.RuntimeHarness harness,TaskAssembler assembler){
        this.service=service;this.runs=runs;this.policy=policy;this.harness=harness;this.assembler=assembler;
        workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"feedback-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Scheduled(scheduler="maintenanceScheduler", fixedDelay=500)
    public void tick(){
        try {
            for(var run:runs.waiting()){
                var op=run.snapshot().operation();if(op==null || !op.kind().equals("feedback-analysis"))continue;
                try{
                    assembler.verify(run.task());
                    if(!policy.enabled()){service.cancel(op.id());run.fail("FEEDBACK_ANALYSIS_DISABLED");continue;}
                    var job=service.forRun(run.id);if(job==null || !job.id().equals(op.id())){run.fail("OPERATION_NOT_FOUND");continue;}
                    if(job.phase()==Phase.READY){
                        run.emit("PROGRESS",Map.of("stage","feedback_analysis","phase","READY","completed_work",job.completedWork(),"model_calls",job.modelCalls(),"charged_tokens",job.chargedTokens()));
                        var asset=service.asset(job.id(),run.owner,FeedbackTaskPolicy.request(run.task().specification()));var evidence=FeedbackEvidenceBinding.bind(run,asset);
                        var result=new ToolResult("analyze_feedback",Map.of("status","READY","evidence_id",evidence.id(),"summary",service.read(asset,"summary",0,1)),evidence.id());
                        runs.resumeOperation(run,op,result);progress.remove(run.id);continue;
                    }
                    if(job.phase()==Phase.FAILED){run.fail(job.errorCode()==null?"ANALYSIS_FAILED":job.errorCode());continue;}
                    if(job.phase()==Phase.CANCELLED){run.cancel();continue;}
                    Long old=progress.put(run.id,job.version());if(old==null || old!=job.version())run.emit("PROGRESS",Map.of("stage",job.phase()==Phase.WAITING_AUTH?"analysis_waiting_auth":"feedback_analysis","phase",job.phase().name(),"completed_work",job.completedWork(),"model_calls",job.modelCalls(),"charged_tokens",job.chargedTokens()));
                    if(MemoryFeedbackJobStore.active(job.phase()) && submitted.add(job.id())){
                        try{workers.execute(()->{try{harness.stage(run,"feedback.work",null,null,Map.of("analysis.job_id",job.id().toString(),"analysis.phase",job.phase().name()),()->{service.advance(job.id(),run.cancellation());return null;});}finally{submitted.remove(job.id());}});}
                        catch(RejectedExecutionException full){submitted.remove(job.id());}
                    }
                }catch(AgentException e){
                    if(Set.of("AUTH_CONTEXT_EXPIRED","UNAUTHORIZED").contains(e.code()))run.emit("PROGRESS",Map.of("stage","analysis_waiting_auth"));
                    else if(!Set.of("STORAGE_UNAVAILABLE","OBJECT_STORE_UNAVAILABLE").contains(e.code()))run.fail(e.code());
                }
            }
            // A crash or cancellation between job creation and run suspension must not leave paid work orphaned.
            for(var job:service.active())if(!submitted.contains(job.id())){
                try{var run=runs.owned(job.runId(),job.owner());if(AgentTypes.terminal(run.snapshot().status()))service.cancel(job.id());}
                catch(AgentException e){if(Set.of("RUN_NOT_FOUND","SESSION_NOT_FOUND","FORBIDDEN").contains(e.code()))service.cancel(job.id());}
            }
        }catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("feedback_scheduler_unavailable type={} location={}",unavailable.getClass().getSimpleName(),java.util.Arrays.stream(unavailable.getStackTrace()).filter(f->f.getClassName().startsWith("com.tianmu.")).limit(5).toList());}
    }
    @PreDestroy public void close(){workers.shutdown();}
}
