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
public final class FeedbackPreparationCoordinator implements com.tianmu.agent.runtime.spi.OperationHandler {
    private final com.tianmu.agent.feedback.FeedbackRuntimeProperties governance;
    private final FeedbackPreparationService preparation;private final FeedbackAnalysisService analysis;private final RunService runs;
    private final TaskAssembler assembler;private final com.tianmu.agent.harness.RuntimeHarness harness;
    private final Set<String> submitted=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers;
    public FeedbackPreparationCoordinator(FeedbackPreparationService preparation,FeedbackAnalysisService analysis,RunService runs,TaskAssembler assembler,com.tianmu.agent.harness.RuntimeHarness harness) { this(preparation, analysis, runs, assembler, harness, com.tianmu.agent.feedback.FeedbackRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public FeedbackPreparationCoordinator(FeedbackPreparationService preparation,FeedbackAnalysisService analysis,RunService runs,TaskAssembler assembler,com.tianmu.agent.harness.RuntimeHarness harness, com.tianmu.agent.feedback.FeedbackRuntimeProperties governance) { this.governance=governance;workers=new ThreadPoolExecutor(governance.preparationWorkers(),governance.preparationWorkers(),governance.workerKeepAliveSeconds(),TimeUnit.SECONDS,new ArrayBlockingQueue<>(governance.preparationQueue()),r->{var t=new Thread(r,"feedback-source");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        this.preparation=preparation;this.analysis=analysis;this.runs=runs;this.assembler=assembler;this.harness=harness;
    }
    public Set<String> kinds(){return Set.of("feedback-sources","feedback-materials");}
    public void tick(){resume(runs.waiting());}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelayString="${agent.feedback.runtime.poll-millis:500}")
    public void advanceSubmissions(){resume(runs.running());}
    public void resume(List<RunState> waiting){
        for(var run:waiting){
            boolean running=run.snapshot().status()==Status.RUNNING;
            var op=run.snapshot().operation();if(!running && (op==null || !kinds().contains(op.kind())))continue;
            if(running && run.task()==null)continue;
            try {
                var job=analysis.forRun(run.id);
                if(running){
                    // A frozen, durable materials request authorizes preparation, never early delivery.
                    if(job==null || !FeedbackCompression.representativeVersion(job.request().rulesVersion())
                            || preparation.preparationWork(job.id(),FeedbackPreparationStore.Step.MATERIALS)==null)continue;
                    analysis.owned(job.id(),run.owner);
                    if(!job.request().equals(FeedbackTaskPolicy.compressedRequest(run.task().specification())))throw new AgentException("TASK_CONSTRAINT_VIOLATION","Material request changed");
                    run.checkpoint();run.verifyMemory();
                }else if(job==null || !job.id().equals(op.id())){run.fail("OPERATION_NOT_FOUND");continue;}
                assembler.verify(run.task());
                if(job.phase()==Phase.FAILED){preparation.settle(job.id());run.fail(job.errorCode());continue;}if(job.phase()==Phase.CANCELLED){preparation.settle(job.id());run.cancel();continue;}
                if(job.phase()==Phase.WAITING_AUTH)continue;
                List<ToolExchange> pending=running?List.of():run.execution().exchanges().stream().filter(e->op.equals(e.result().pending())).toList();
                if(!running && pending.isEmpty())throw new AgentException("STORAGE_CORRUPT","Missing source continuation");
                boolean ready=running?preparation.materialsReady(job.id()):op.kind().equals("feedback-materials")?preparation.materialsReady(job.id()):pending.stream().allMatch(e->preparation.ready(job.id(),source(e.call().name())));
                if(ready){
                    if(running)continue;
                    String key=job.id()+":DELIVERY";
                    if(submitted.add(key))try{workers.execute(()->{
                        try{harness.execute(run,()->harness.stage(run,"feedback.materials.deliver",null,null,deliveryReferences(run,job,pending),()->{
                            var results=new ArrayList<ToolResult>();
                            for(var exchange:pending){if(op.kind().equals("feedback-materials")){if(!exchange.call().name().equals("prepare_feedback_materials"))throw new AgentException("STORAGE_CORRUPT","Unexpected materials continuation");results.add(FeedbackMaterialEvidence.bind(run,job,preparation.materials(job.id(),run.owner,run.callControl())));}else{var source=source(exchange.call().name());results.add(FeedbackSourceEvidence.bind(run,job,preparation.result(job.id(),run.owner,source,run.callControl())));}}
                            runs.resumeOperations(run,op,results);
                            return true;
                        }));}catch(AgentException failure){handle(run,failure);}catch(RuntimeException failure){unexpected(run,failure);}
                        finally{submitted.remove(key);}
                    });}catch(RejectedExecutionException full){submitted.remove(key);}
                    continue;
                }
                for(var step:preparation.pending(job.id())){
                    String key=job.id()+":"+step;if(!submitted.add(key))continue;
                    try{workers.execute(()->{
                        try{harness.execute(run,()->{
                            String traceId=io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId();
                            var attrs=new HashMap<String,Object>(FeedbackCausalReferences.dependencies(step,job.request().sources(),traceId,predecessor->preparation.preparationWork(job.id(),predecessor)));attrs.put("analysis.job_id",job.id().toString());attrs.put("analysis.phase",step.name());
                            if(step==FeedbackPreparationStore.Step.SNAPSHOT)preparation.materialTrigger(job.id(),traceId).ifPresent(id->attrs.put("analysis.trigger_span_id",id));
                            harness.stage(run,"feedback.prepare",null,null,attrs,()->{
                                boolean accepted=preparation.advance(job.id(),step,run.cancellation());
                                com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("analysis.accepted",accepted));
                                return Map.of("step",step.name());
                            });
                        });}catch(AgentException failure){/* advance persisted retry/pause/failure; the stage records the error. */}
                        finally{submitted.remove(key);}
                    });}catch(RejectedExecutionException full){submitted.remove(key);}
                }
            }catch(AgentException failure){
                handle(run,failure);
            }catch(RuntimeException failure){unexpected(run,failure);}
        }
    }
    private Map<String,Object> deliveryReferences(RunState run,FeedbackJobStore.Job job,List<ToolExchange> pending){
        var attrs=new HashMap<String,Object>();attrs.put("analysis.job_id",job.id().toString());
        var accepted=preparation.preparationWork(job.id(),FeedbackPreparationStore.Step.MATERIALS);
        String trace=io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId();
        if(accepted!=null && "COMPLETE".equals(accepted.state()) && accepted.acceptedBy()!=null && trace.equals(accepted.acceptedBy().traceId()))
            attrs.put("analysis.predecessor_span_ids",List.of(accepted.acceptedBy().spanId()));
        if(pending.size()==1){String group=pending.get(0).call().providerGroupId();
            if(group!=null && !group.isBlank()){
                attrs.put("tool.join_group_id",group);
                attrs.put("tool.join_batch_id",ToolBatchLedger.id(run));
                // The host batch is authoritative; provider IDs are not unique across model turns.
                // WAITING is entered only after every native result in this batch has been paired.
            }
        }
        return attrs;
    }
    private static void handle(RunState run,AgentException failure){
        if(!Set.of("STORAGE_UNAVAILABLE","OBJECT_STORE_UNAVAILABLE","AUTH_CONTEXT_EXPIRED","UNAUTHORIZED").contains(failure.code()))run.fail(failure.code());
    }
    private static void unexpected(RunState run,RuntimeException failure){org.slf4j.LoggerFactory.getLogger(FeedbackPreparationCoordinator.class).error("preparation_resume_failed run={} type={} location={}",run.id,failure.getClass().getSimpleName(),Arrays.stream(failure.getStackTrace()).filter(f->f.getClassName().startsWith("com.tianmu.")).limit(4).toList());run.fail("PREPARATION_RESUME_FAILED");}
    private static Source source(String tool){return switch(tool){case "analyze_comments"->Source.COMMENT;case "analyze_danmaku"->Source.DANMAKU;default->throw new AgentException("STORAGE_CORRUPT","Unexpected source continuation");};}
    @PreDestroy public void close(){workers.shutdownNow();}
}
