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

/** Bounded source workers. Scheduler never waits on snapshot export, Embedding or Milvus. */
@Component
public final class FeedbackPreparationCoordinator {
    private final FeedbackPreparationService preparation;private final FeedbackAnalysisService analysis;private final RunService runs;
    private final TaskAssembler assembler;private final com.tianmu.agent.harness.RuntimeHarness harness;
    private final Set<String> submitted=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"feedback-source");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public FeedbackPreparationCoordinator(FeedbackPreparationService preparation,FeedbackAnalysisService analysis,RunService runs,TaskAssembler assembler,com.tianmu.agent.harness.RuntimeHarness harness){
        this.preparation=preparation;this.analysis=analysis;this.runs=runs;this.assembler=assembler;this.harness=harness;
    }
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=500)
    public void tick(){
        for(var run:runs.waiting()){
            var op=run.snapshot().operation();if(op==null || !op.kind().equals("feedback-sources"))continue;
            try {
                assembler.verify(run.task());var job=analysis.forRun(run.id);
                if(job==null || !job.id().equals(op.id())){run.fail("OPERATION_NOT_FOUND");continue;}
                if(job.phase()==Phase.FAILED){preparation.settle(job.id());run.fail(job.errorCode());continue;}if(job.phase()==Phase.CANCELLED){preparation.settle(job.id());run.cancel();continue;}
                if(job.phase()==Phase.WAITING_AUTH)continue;
                var pending=run.execution().exchanges().stream().filter(e->op.equals(e.result().pending())).toList();
                if(pending.isEmpty())throw new AgentException("STORAGE_CORRUPT","Missing source continuation");
                boolean ready=pending.stream().allMatch(e->preparation.ready(job.id(),source(e.call().name())));
                if(ready){
                    String key=job.id()+":DELIVERY";
                    if(submitted.add(key))try{workers.execute(()->{
                        try{harness.execute(run,()->{
                            var results=new ArrayList<ToolResult>();
                            for(var exchange:pending){var source=source(exchange.call().name());results.add(FeedbackSourceEvidence.bind(run,job,preparation.result(job.id(),run.owner,source,run.callControl())));}
                            runs.resumeOperations(run,op,results);
                        });}catch(AgentException failure){handle(run,failure);}catch(RuntimeException failure){run.fail("PREPARATION_RESUME_FAILED");}
                        finally{submitted.remove(key);}
                    });}catch(RejectedExecutionException full){submitted.remove(key);}
                    continue;
                }
                for(var step:preparation.pending(job.id())){
                    String key=job.id()+":"+step;if(!submitted.add(key))continue;
                    try{workers.execute(()->{
                        try{harness.execute(run,()->harness.stage(run,"feedback.prepare",null,null,Map.of("analysis.job_id",job.id().toString(),"analysis.phase",step.name()),()->{
                            preparation.advance(job.id(),step,run.cancellation());return Map.of("step",step.name());
                        }));}catch(AgentException failure){/* advance persisted retry/pause/failure; the stage records the error. */}
                        finally{submitted.remove(key);}
                    });}catch(RejectedExecutionException full){submitted.remove(key);}
                }
            }catch(AgentException failure){
                handle(run,failure);
            }catch(RuntimeException failure){run.fail("PREPARATION_RESUME_FAILED");}
        }
    }
    private static void handle(RunState run,AgentException failure){
        if(!Set.of("STORAGE_UNAVAILABLE","OBJECT_STORE_UNAVAILABLE","AUTH_CONTEXT_EXPIRED","UNAUTHORIZED").contains(failure.code()))run.fail(failure.code());
    }
    private static Source source(String tool){return switch(tool){case "analyze_comments"->Source.COMMENT;case "analyze_danmaku"->Source.DANMAKU;default->throw new AgentException("STORAGE_CORRUPT","Unexpected source continuation");};}
    @PreDestroy public void close(){workers.shutdownNow();}
}
