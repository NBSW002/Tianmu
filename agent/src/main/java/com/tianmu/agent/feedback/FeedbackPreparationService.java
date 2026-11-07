package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.*;
import java.time.*;
import java.util.*;
import static com.tianmu.agent.feedback.FeedbackPreparationStore.Step;

/** Source tools share one frozen export. Only the report stage starts semantic reading. */
public final class FeedbackPreparationService {
    private final com.tianmu.agent.feedback.FeedbackRuntimeProperties governance;
    public static final String VERSION="feedback-pipeline-v2";
    public record Prepared(Source source,Blob manifest,FeedbackPlanner.Planned plan,int exactExpressions,int clusters,String method) {}
    private final FeedbackAnalysisService analysis;private final FeedbackPreparationStore store;private final FeedbackObjects objects;
    private final SharedFeedbackSnapshot snapshots;private final FeedbackPlanner planner;private final FeedbackClusters clusters;
    public FeedbackPreparationService(FeedbackAnalysisService analysis,FeedbackPreparationStore store,FeedbackObjects objects,FeedbackPolicy policy,FeedbackClusters clusters) { this(analysis, store, objects, policy, clusters, com.tianmu.agent.feedback.FeedbackRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public FeedbackPreparationService(FeedbackAnalysisService analysis,FeedbackPreparationStore store,FeedbackObjects objects,FeedbackPolicy policy,FeedbackClusters clusters, com.tianmu.agent.feedback.FeedbackRuntimeProperties governance) { this.governance=governance;
        this.analysis=analysis;this.store=store;this.objects=objects;this.snapshots=analysis.sharedSnapshots();this.planner=new FeedbackPlanner(objects,policy);this.clusters=clusters;
    }
    public FeedbackJobStore.Job request(UUID run,AccessScope owner,Request request,VideoContent.Facts video,Instant deadline,Source source){
        if(!VERSION.equals(request.rulesVersion()) || !request.sources().contains(source))throw new AgentException("TASK_CONSTRAINT_VIOLATION","Source outside frozen pipeline");
        var job=analysis.submit(run,owner,request,video,deadline);store.request(job.id(),Step.SNAPSHOT);store.request(job.id(),Step.valueOf(source.name()));return job;
    }
    public boolean ready(UUID job,Source source){var w=store.get(job,Step.valueOf(source.name()));return w!=null && w.state().equals("COMPLETE");}
    public List<Step> pending(UUID job){
        var snapshot=store.get(job,Step.SNAPSHOT);if(snapshot==null)return List.of();
        if(!snapshot.state().equals("COMPLETE"))return claimable(snapshot)?List.of(Step.SNAPSHOT):List.of();
        return Arrays.stream(Step.values()).filter(s->s!=Step.SNAPSHOT).filter(s->{var w=store.get(job,s);return w!=null && claimable(w);}).toList();
    }
    private static boolean claimable(FeedbackPreparationStore.Work w){return Set.of("PENDING","RUNNING").contains(w.state()) && !Instant.now().isBefore(w.leaseUntil());}
    public void settle(UUID id){
        var job=analysis.forJob(id);if(job==null)return;
        if(job.phase()==Phase.FAILED || job.phase()==Phase.CANCELLED)store.settle(id,job.phase().name());
    }
    public Prepared result(UUID id,AccessScope owner,Source source,CallControl control){
        var job=analysis.owned(id,owner);var w=store.get(id,Step.valueOf(source.name()));
        if(w==null || !w.state().equals("COMPLETE"))throw new AgentException("ANALYSIS_NOT_READY","Source evidence is not ready");
        var result=objects.get(w.result(),Prepared.class);var manifest=store.get(id,Step.SNAPSHOT);
        if(result.source()!=source || manifest==null || !result.manifest().equals(manifest.result()))throw new AgentException("STORAGE_CORRUPT","Source evidence uses a different snapshot");
        snapshots.resolve(result.manifest(),job.runId(),owner,job.request(),control);return result;
    }
    public void advance(UUID id,Step step,CancellationToken cancellation){
        FeedbackPreparationStore.Work claim=null;
        try {
            var job=analysis.forJob(id);if(job==null || !MemoryFeedbackJobStore.active(job.phase()))return;
            analysis.owned(id,job.owner());
            if(!Instant.now().isBefore(job.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Preparation deadline exceeded");
            claim=store.claim(id,step,Instant.now(),Duration.ofSeconds(governance.preparationLeaseSeconds()));if(claim==null)return;
            var control=new CallControl(job.deadline().isBefore(claim.leaseUntil())?job.deadline():claim.leaseUntil(),cancellation);
            Blob result;
            if(step==Step.SNAPSHOT)result=snapshots.materialize(id,job.runId(),job.owner(),job.request(),control);
            else {
                var manifest=store.get(id,Step.SNAPSHOT);if(manifest==null || !manifest.state().equals("COMPLETE"))throw new AgentException("ANALYSIS_NOT_READY","Shared snapshot is not ready");
                Source source=Source.valueOf(step.name());var rows=new ArrayList<Unit>();
                var partition=snapshots.read(manifest.result(),job.runId(),job.owner(),job.request(),source,control,rows::addAll);
                var organized=clusters.organize(id,job.owner(),job.request(),source,rows,control,()->store.reserveEmbedding(id,512));
                var collector=planner.collector(id,job.request(),organized.membership());collector.accept(organized.ordered());
                var shared=snapshots.resolve(manifest.result(),job.runId(),job.owner(),job.request(),control);
                var planned=collector.finish(new FeedbackSnapshotPort.Receipt(shared.dataAsOf(),partition.counts()));
                result=objects.put(id,new Prepared(source,manifest.result(),planned,organized.exactExpressions(),organized.clusters(),organized.method()));
            }
            control.checkpoint();analysis.owned(id,job.owner());
            if(Instant.now().isBefore(control.deadline())){if(!store.complete(claim,result))throw new AgentException("PREPARATION_CLAIM_LOST","Preparation result was not accepted");}
            else throw new AgentException("DEADLINE_EXCEEDED","Preparation deadline exceeded");
        }catch(AgentException failure){
            if(failure.code().equals("PREPARATION_CLAIM_LOST")){settle(id);throw failure;}
            if(cancellation.cancelled())analysis.cancel(id);
            else if(claim!=null && Set.of("VECTOR_STORE_UNAVAILABLE","VECTOR_RATE_LIMITED","EMBEDDING_UNAVAILABLE","EMBEDDING_RATE_LIMITED","BUSINESS_UNAVAILABLE","BUSINESS_TIMEOUT","OBJECT_STORE_UNAVAILABLE","STORAGE_UNAVAILABLE").contains(failure.code()))store.release(claim);
            else {if(claim!=null && Set.of("UNAUTHORIZED","AUTH_CONTEXT_EXPIRED","AUTHORIZATION_EXPIRED").contains(failure.code()))store.release(claim);analysis.preparationFailed(id,failure.code());}
            settle(id);
            throw failure;
        }catch(RuntimeException failure){analysis.preparationFailed(id,"PREPARATION_FAILED");settle(id);throw new AgentException("PREPARATION_FAILED","Source preparation failed");}
    }
    public FeedbackJobStore.Job startReport(UUID id,AccessScope owner,CallControl control){
        var job=analysis.owned(id,owner);
        if(!VERSION.equals(job.request().rulesVersion()))throw new AgentException("TASK_CONSTRAINT_VIOLATION","Expected source preparation job");
        var inputs=new ArrayList<Blob>();var chunks=new ArrayList<Blob>();Blob manifest=null;
        for(var source:Source.values())if(job.request().sources().contains(source)){
            var part=result(id,owner,source,control);if(manifest!=null && !manifest.equals(part.manifest()))throw new AgentException("INVALID_EVIDENCE","Sources have different snapshots");
            manifest=part.manifest();inputs.addAll(part.plan().inputs());chunks.addAll(part.plan().snapshot().chunks());
        }
        var shared=snapshots.resolve(manifest,job.runId(),owner,job.request(),control);
        if(job.snapshot()==null)analysis.initializePrepared(job,new Snapshot("feedback-snapshot-v1",shared.dataAsOf(),shared.counts(),chunks,manifest.sha256()),inputs);
        return analysis.forJob(id);
    }
}
