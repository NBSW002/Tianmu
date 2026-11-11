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
public final class FeedbackOperationCoordinator implements com.tianmu.agent.runtime.spi.OperationHandler {
    private final com.tianmu.agent.feedback.FeedbackRuntimeProperties governance;
    private final FeedbackAnalysisService service;private final RunService runs;
    private final com.tianmu.agent.harness.RuntimeHarness harness;
    private final TaskAssembler assembler;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.runtime.store.ReportStore reportStore;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private FeedbackPreparationService preparation;
    private final ThreadPoolExecutor workers;
    private final Set<String> submitted=ConcurrentHashMap.newKeySet();private final Map<UUID,Long> progress=new ConcurrentHashMap<>();
    public FeedbackOperationCoordinator(FeedbackAnalysisService service,RunService runs,com.tianmu.agent.harness.RuntimeHarness harness,TaskAssembler assembler) { this(service, runs, harness, assembler, com.tianmu.agent.feedback.FeedbackRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public FeedbackOperationCoordinator(FeedbackAnalysisService service,RunService runs,com.tianmu.agent.harness.RuntimeHarness harness,TaskAssembler assembler, com.tianmu.agent.feedback.FeedbackRuntimeProperties governance) { this.governance=governance;
        this.service=service;this.runs=runs;this.harness=harness;this.assembler=assembler;
        workers=new ThreadPoolExecutor(Math.max(2,service.parallelism()),Math.max(2,service.parallelism()),governance.workerKeepAliveSeconds(),TimeUnit.SECONDS,new ArrayBlockingQueue<>(Math.max(2,service.parallelism()*2)),r->{var t=new Thread(r,"feedback-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public Set<String> kinds(){return Set.of("feedback-analysis","feedback-report","representative-report");}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelayString="${agent.feedback.runtime.poll-millis:500}")
    public void reconcileOrphans(){resume(List.of());}
    public void tick(){resume(runs.waiting());}
    public void resume(List<RunState> waiting){
        try {
            for(var run:waiting){
                var op=run.snapshot().operation();if(op==null || !kinds().contains(op.kind()))continue;
                try{
                    assembler.verify(run.task());
                    var job=service.forRun(run.id);if(job==null || !job.id().equals(op.id())){run.fail("OPERATION_NOT_FOUND");continue;}
                    run.backgroundModelCalls(job.modelCalls());
                    if(job.phase()==Phase.READY){
                        if(submitted.add(job.id()+":DELIVER")){
                            try{workers.execute(()->{
                                try{harness.execute(run,()->harness.stage(run,"feedback.deliver",null,null,Map.of("analysis.job_id",job.id().toString()),()->{
                                    deliver(run,op,job);return true;
                                }));}
                                catch(AgentException failure){if(!Set.of("STORAGE_UNAVAILABLE","OBJECT_STORE_UNAVAILABLE","AUTH_CONTEXT_EXPIRED","UNAUTHORIZED").contains(failure.code()))run.fail(failure.code());}
                                catch(RuntimeException failure){run.fail("REPORT_DELIVERY_FAILED");}
                                finally{submitted.remove(job.id()+":DELIVER");}
                            });}catch(RejectedExecutionException full){submitted.remove(job.id()+":DELIVER");}
                        }
                        continue;
                    }
                    if(job.phase()==Phase.FAILED){run.fail(job.errorCode()==null?"ANALYSIS_FAILED":job.errorCode());continue;}
                    if(job.phase()==Phase.CANCELLED){run.cancel();continue;}
                    Long old=progress.put(run.id,job.version());if(old==null || old!=job.version())run.emit("PROGRESS",Map.of("stage",job.phase()==Phase.WAITING_AUTH?"analysis_waiting_auth":"feedback_analysis","phase",job.phase().name(),"completed_work",job.completedWork(),"model_calls",job.modelCalls(),"charged_tokens",job.chargedTokens()));
                    for(int slot=0;slot<service.dispatchSlots(job);slot++){
                    String workKey=job.id()+":WORK:"+slot;
                    if(MemoryFeedbackJobStore.active(job.phase()) && submitted.add(workKey)){
                        long enqueued=System.nanoTime();
                        try{workers.execute(()->{try{harness.execute(run,()->harness.stage(run,"feedback.work",null,null,Map.of("analysis.job_id",job.id().toString(),"analysis.phase",job.phase().name(),"execution.queue_ms",Math.max(0,(System.nanoTime()-enqueued)/1_000_000)),()->{
                            var failure=service.advance(job.id(),run.cancellation(),()->run.backgroundModelCalls(service.forRun(run.id).modelCalls()));
                            var current=service.forRun(run.id);run.backgroundModelCalls(current.modelCalls());
                            if(failure.isPresent())throw failure.get();
                            return Map.of("phase",current.phase().name(),"completed_work",current.completedWork(),"model_calls",current.modelCalls(),"charged_tokens",current.chargedTokens());
                        }));}catch(AgentException failure){/* Work already persisted its retry/pause/failure; Harness records this attempt. */}finally{submitted.remove(workKey);}});}
                        catch(RejectedExecutionException full){submitted.remove(workKey);}
                    }
                    }
                }catch(AgentException e){
                    if(Set.of("AUTH_CONTEXT_EXPIRED","UNAUTHORIZED").contains(e.code()))run.emit("PROGRESS",Map.of("stage","analysis_waiting_auth"));
                    else if(!Set.of("STORAGE_UNAVAILABLE","OBJECT_STORE_UNAVAILABLE").contains(e.code()))run.fail(e.code());
                }catch(RuntimeException bug){
                    org.slf4j.LoggerFactory.getLogger(getClass()).error("feedback_resume_failed run={} type={}",run.id,bug.getClass().getSimpleName());
                    run.fail("ANALYSIS_RESUME_FAILED");
                }
            }
            // A crash or cancellation between job creation and run suspension must not leave paid work orphaned.
            for(var job:service.active())if(submitted.stream().noneMatch(key->key.startsWith(job.id()+":"))){
                try{var run=runs.owned(job.runId(),job.owner());if(AgentTypes.terminal(run.snapshot().status()))cancelOrphan(job.id());}
                catch(AgentException e){if(Set.of("RUN_NOT_FOUND","SESSION_NOT_FOUND","FORBIDDEN").contains(e.code()))cancelOrphan(job.id());}
            }
        }catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("feedback_scheduler_unavailable type={} location={}",unavailable.getClass().getSimpleName(),java.util.Arrays.stream(unavailable.getStackTrace()).filter(f->f.getClassName().startsWith("com.tianmu.")).limit(5).toList());}
    }
    private void cancelOrphan(UUID job){service.cancel(job);if(preparation!=null)preparation.settle(job);}
    private void deliver(RunState run,PendingOperation op,FeedbackJobStore.Job job){
        run.checkpoint();
        var request=op.kind().equals("representative-report")?FeedbackTaskPolicy.compressedRequest(run.task().specification(),job.request().rulesVersion()):op.kind().equals("feedback-report")?FeedbackTaskPolicy.pipelineRequest(run.task().specification()):FeedbackTaskPolicy.request(run.task().specification());
        var asset=service.asset(job.id(),run.owner,request);var evidence=FeedbackEvidenceBinding.bind(run,asset);
        ToolResult result;
        if(op.kind().equals("representative-report")){
            if(preparation==null)throw new AgentException("COMPRESSION_DISABLED","Representative report service missing");
            var reportJson=preparation.reportJson(job.id(),run.owner,run.callControl());
            traceReportEvidence(reportJson);
            var report=new ReportArtifacts(reportStore).save(run,evidence.id(),reportJson.html());
            result=new ToolResult("generate_feedback_report",Map.of("status","READY","evidence_id",evidence.id(),"artifact_id",report.artifactId(),"url",report.url(),"json_url",report.url()+"/json","summary",reportJson.summary(),"topics",reportJson.topics().stream().map(FeedbackReportJson.TopicView::title).toList(),"method",reportJson.method(),"demo",asset.model().equals("demo-scripted-feedback")),evidence.id());
        }else if(op.kind().equals("feedback-report")){
            var report=new ReportArtifacts(reportStore).save(run,evidence.id(),service.report(asset));
            result=new ToolResult("create_feedback_report",Map.of("status","READY","evidence_id",evidence.id(),"artifact_id",report.artifactId(),"url",report.url(),"demo",asset.model().equals("demo-scripted-feedback")),evidence.id());
        }else result=new ToolResult("analyze_feedback",Map.of("status","READY","evidence_id",evidence.id(),"summary",service.read(asset,"summary",0,1)),evidence.id());
        runs.resumeOperation(run,op,result);progress.remove(run.id);
    }
    /** Only records the actual report selection; temporal proximity is not a semantic relevance verdict. */
    private static void traceReportEvidence(FeedbackReportJson report){
        var representatives=new ArrayList<String>();var frames=new ArrayList<String>();var frameModes=new ArrayList<String>();var video=report.videoDetails();
        for(int i=0;i<report.topics().size();i++){
            var topic=report.topics().get(i);String index=Integer.toString(i+1);
            representatives.add(index+":"+topic.representatives().stream().map(FeedbackCompression.Representative::id).collect(java.util.stream.Collectors.joining(":")));
            if(video==null)continue;var selected=new LinkedHashSet<Long>();
            frameModes.add(index+":"+(topic.selectedFrameMs()==null?"TEMPORAL":"SEMANTIC_REVIEW"));
            if(topic.selectedFrameMs()!=null)selected.addAll(topic.selectedFrameMs());
            else for(var rep:topic.representatives()){
                Long at=rep.playbackMs();if(at==null || at<video.startMs() || at>=video.endMs())continue;
                video.moments().stream().filter(m->Math.abs(m.atMs()-at)<=video.momentMatchMaxGapMs()).min(Comparator.comparingLong(m->Math.abs(m.atMs()-at))).ifPresent(m->selected.add(m.atMs()));if(selected.size()>=2)break;
            }
            frames.add(index+":"+(selected.isEmpty()?"NONE":selected.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(":"))));
        }
        com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("analysis.report_representatives",representatives,"video.topic_nearby_frames",frames,"video.topic_frame_modes",frameModes));
    }
    @PreDestroy public void close(){workers.shutdown();}
}
