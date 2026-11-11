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
    private FeedbackCompression compression;
    private FeedbackRerankPreparation rerank;
    private boolean rerankEnabled;
    public record SourcePart(Source source,List<Blob> chunks,List<FeedbackCompression.Excluded> excluded,FeedbackCompression.SourceStats stats){public SourcePart{chunks=List.copyOf(chunks);excluded=List.copyOf(excluded);}}
    private FeedbackCompression.SourceMaterial loadPart(Blob ref){var part=objects.get(ref,SourcePart.class);var rows=new ArrayList<Unit>();for(var chunk:part.chunks())rows.addAll(Arrays.asList(objects.get(chunk,Unit[].class)));return new FeedbackCompression.SourceMaterial(part.source(),rows,part.excluded(),part.stats());}
    static SourcePart boundedPart(FeedbackObjects objects,SourcePart part){return part.stats()!=null && objects.bytes(part)>1048576?new SourcePart(part.source(),part.chunks(),part.excluded(),null):part;}
    static FeedbackCompression.Material boundedMaterial(FeedbackObjects objects,FeedbackCompression.Material value){return value.stats()!=null && objects.bytes(value)>1048576?value.withoutStats():value;}
    private Blob savePart(UUID id,FeedbackCompression.SourceMaterial part){var chunks=new ArrayList<Blob>();var chunk=new ArrayList<Unit>();int bytes=0;for(var u:part.eligible()){int size=objects.bytes(u);if(bytes+size>524288 && !chunk.isEmpty()){chunks.add(objects.put(id,List.copyOf(chunk)));chunk.clear();bytes=0;}chunk.add(u);bytes+=size;}if(!chunk.isEmpty())chunks.add(objects.put(id,chunk));return objects.put(id,boundedPart(objects,new SourcePart(part.source(),chunks,part.excluded(),part.stats())));}
    public FeedbackPreparationService(FeedbackAnalysisService analysis,FeedbackPreparationStore store,FeedbackObjects objects,FeedbackPolicy policy,FeedbackClusters clusters,com.tianmu.agent.feedback.FeedbackRuntimeProperties governance,FeedbackCompression compression){
        this(analysis,store,objects,policy,clusters,governance);this.compression=compression;analysis.compressionRevision(compression.revision());analysis.fiveCompressionRevision(compression.fiveRevision());
        analysis.rerankCompressionRevision(compression.materialRevision(FeedbackCompression.RERANK_VERSION));
        rerank=new FeedbackRerankPreparation(store,objects,null,FeedbackReusePolicy.defaults());
        analysis.representativeMethodProvider(id->{var work=store.get(id,Step.MATERIALS);if(work==null || !"COMPLETE".equals(work.state()))throw new AgentException("RESULT_NOT_FOUND","Representative method unavailable");return objects.get(work.result(),FeedbackCompression.Material.class).summary();});
    }
    public boolean compressionEnabled(){return compression!=null && compression.policy().enabled();}
    void configureRerank(boolean enabled,FeedbackReranker model,FeedbackReusePolicy reuse){
        if(enabled && (model==null || !compressionEnabled()))throw new IllegalArgumentException("Enabled rerank requires representative compression and a provider");
        rerankEnabled=enabled;
        rerank=new FeedbackRerankPreparation(store,objects,model,reuse);
    }
    public String reportRulesVersion(UUID run){
        var job=analysis.forRun(run);
        return job!=null && FeedbackCompression.representativeVersion(job.request().rulesVersion())
                ?job.request().rulesVersion():rerankEnabled?FeedbackCompression.RERANK_VERSION:FeedbackCompression.FIVE_VERSION;
    }
    public FeedbackReportJson reportJson(UUID id,AccessScope owner,CallControl control){
        var job=analysis.owned(id,owner);if(job.phase()!=Phase.READY)throw new AgentException("ANALYSIS_NOT_READY","Report analysis not ready");
        if(!FeedbackCompression.representativeVersion(job.request().rulesVersion()))throw new AgentException("RESULT_NOT_FOUND","Structured representative report unavailable");
        store.request(id,Step.REPORT_JSON);var existing=store.get(id,Step.REPORT_JSON);
        if(existing.state().equals("COMPLETE"))return objects.get(existing.result(),FeedbackReportJson.class);
        var claim=store.claim(id,Step.REPORT_JSON,Instant.now(),Duration.ofSeconds(governance.preparationLeaseSeconds()));
        if(claim==null)throw new AgentException("STORAGE_UNAVAILABLE","Report JSON persistence is in progress");
        try{var asset=analysis.asset(id,owner,job.request());var value=analysis.compressedReport(asset,materials(id,owner,control));control.checkpoint();analysis.owned(id,owner);
            if(!store.complete(claim,objects.put(id,value,control)))throw new AgentException("STORAGE_UNAVAILABLE","Report JSON acceptance failed");return value;
        }catch(RuntimeException failure){store.release(claim);throw failure;}
    }
    public FeedbackReportJson storedReportJson(UUID id,AccessScope owner){var job=analysis.owned(id,owner);if(job.phase()!=Phase.READY || !FeedbackCompression.representativeVersion(job.request().rulesVersion()))throw new AgentException("RESULT_NOT_FOUND","Structured representative report unavailable");var work=store.get(id,Step.REPORT_JSON);if(work==null || !work.state().equals("COMPLETE"))throw new AgentException("RESULT_NOT_FOUND","Report JSON not saved");return objects.get(work.result(),FeedbackReportJson.class);}
    public FeedbackJobStore.Job requestMaterials(UUID run,AccessScope owner,Request request,Instant deadline){
        if(!compressionEnabled() || !FeedbackCompression.representativeVersion(request.rulesVersion()))throw new AgentException("COMPRESSION_DISABLED","Representative pipeline is not enabled");
        var job=analysis.forRun(run);if(job!=null){job=analysis.owned(job.id(),owner);if(!job.request().equals(request))throw new AgentException("TASK_CONSTRAINT_VIOLATION","Material request changed");analysis.verifyProtocol(job);}else job=analysis.submit(run,owner,request,null,deadline);
        var trigger=FeedbackPreparationStore.Reference.current();
        com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("analysis.job_id",job.id().toString()));
        store.request(job.id(),Step.SNAPSHOT,trigger);
        for(var source:request.sources())store.request(job.id(),Step.valueOf(source.name()));store.request(job.id(),Step.MATERIALS);return job;
    }
    public Optional<String> materialTrigger(UUID job,String traceId){var work=store.get(job,Step.SNAPSHOT);var trigger=work==null?null:work.triggeredBy();return trigger!=null && trigger.traceId().equals(traceId)?Optional.of(trigger.spanId()):Optional.empty();}
    public FeedbackPreparationStore.Work preparationWork(UUID job,Step step){return store.get(job,step);}
    public boolean materialsReady(UUID id){var w=store.get(id,Step.MATERIALS);return w!=null && w.state().equals("COMPLETE");}
    public FeedbackCompression.Material materials(UUID id,AccessScope owner,CallControl control){
        var job=analysis.owned(id,owner);var w=store.get(id,Step.MATERIALS);if(w==null || !w.state().equals("COMPLETE"))throw new AgentException("ANALYSIS_NOT_READY","Representative materials are not ready");
        var result=objects.get(w.result(),FeedbackCompression.Material.class);
        if(!job.request().rulesVersion().equals(result.version()) || !compression.materialRevision(result.version()).equals(result.protocolRevision()))throw new AgentException("RESOURCE_VERSION_MISMATCH","Material policy changed");
        snapshots.resolve(result.manifest(),job.runId(),owner,job.request(),control);return result;
    }
    public FeedbackJobStore.Job startCompressedReport(UUID id,AccessScope owner,VideoContent.Facts video,CallControl control){
        var job=analysis.owned(id,owner);var material=materials(id,owner,control);
        if(job.request().grounding()==Grounding.VIDEO_GROUNDED && (video==null || video.videoId()!=job.request().query().videoId()
                || job.request().videoStartMs()!=null && (video.startMs()!=job.request().videoStartMs() || video.endMs()!=job.request().videoEndMs())))throw new AgentException("INVALID_EVIDENCE","Matching video evidence is required");
        if(job.request().grounding()==Grounding.FEEDBACK_ONLY && video!=null)throw new AgentException("TASK_CONSTRAINT_VIOLATION","Video not selected");
        if(job.snapshot()==null){
            var chunks=new ArrayList<Blob>();var assigned=new HashSet<String>();material.clusters().forEach(cluster->assigned.addAll(cluster.members()));
            var people=new HashSet<String>();long[] counts={0,0};
            for(var source:job.request().sources()){
                var part=loadPart(store.get(id,Step.valueOf(source.name())).result());
                var chunk=new ArrayList<Unit>();int bytes=0;
                for(var row:part.eligible()){if(!assigned.contains(row.id()))continue;if(row.userId()!=null)people.add(row.userId());counts[source==Source.COMMENT?0:1]++;int size=objects.bytes(row);if(bytes+size>524288 && !chunk.isEmpty()){chunks.add(objects.put(id,List.copyOf(chunk)));chunk.clear();bytes=0;}chunk.add(row);bytes+=size;}
                if(!chunk.isEmpty())chunks.add(objects.put(id,chunk));
            }
            var shared=snapshots.resolve(material.manifest(),job.runId(),owner,job.request(),control);
            if(counts[0]+counts[1]+material.unclassified().size()!=material.eligibleCounts().total())throw new AgentException("STORAGE_CORRUPT","Bucket and unclassified counts do not reconcile");
            analysis.initializeCompressed(job,new Snapshot("feedback-snapshot-v1",shared.dataAsOf(),new Counts(counts[0],counts[1],0,0,people.size()),chunks,store.get(id,Step.MATERIALS).result().sha256()),video,material.inputs().stream().map(input->objects.put(id,input)).toList());
        }else if(!Objects.equals(video,job.video()))throw new AgentException("INVALID_EVIDENCE","Report video changed");
        return analysis.forJob(id);
    }
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
        var current=analysis.forJob(job);
        if(current!=null && FeedbackCompression.representativeVersion(current.request().rulesVersion())){
            var sourceSteps=current.request().sources().stream().map(s->Step.valueOf(s.name())).toList();
            boolean sourcesReady=sourceSteps.stream().allMatch(s->{var w=store.get(job,s);return w!=null && w.state().equals("COMPLETE");});
            if(sourcesReady){var w=store.get(job,Step.MATERIALS);return w!=null && claimable(w)?List.of(Step.MATERIALS):List.of();}
            return sourceSteps.stream().filter(s->{var w=store.get(job,s);return w!=null && claimable(w);}).toList();
        }
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
    public boolean advance(UUID id,Step step,CancellationToken cancellation){
        FeedbackPreparationStore.Work claim=null;
        try {
            var job=analysis.forJob(id);if(job==null || !MemoryFeedbackJobStore.active(job.phase()))return false;
            analysis.owned(id,job.owner());analysis.verifyProtocol(job);
            if(!Instant.now().isBefore(job.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Preparation deadline exceeded");
            claim=store.claim(id,step,Instant.now(),Duration.ofSeconds(governance.preparationLeaseSeconds()));if(claim==null)return false;
            var control=new CallControl(job.deadline().isBefore(claim.leaseUntil())?job.deadline():claim.leaseUntil(),cancellation);
            Blob result;
            if(step==Step.SNAPSHOT)result=snapshots.materialize(id,job.runId(),job.owner(),job.request(),control);
            else if(FeedbackCompression.representativeVersion(job.request().rulesVersion()) && step==Step.MATERIALS){
                var manifest=store.get(id,Step.SNAPSHOT);if(manifest==null || !manifest.state().equals("COMPLETE"))throw new AgentException("ANALYSIS_NOT_READY","Shared snapshot missing");
                var parts=new ArrayList<FeedbackCompression.SourceMaterial>();
                for(var source:job.request().sources()){var w=store.get(id,Step.valueOf(source.name()));if(w==null || !w.state().equals("COMPLETE"))throw new AgentException("ANALYSIS_NOT_READY","Source preparation incomplete");parts.add(loadPart(w.result()));}
                var shared=snapshots.resolve(manifest.result(),job.runId(),job.owner(),job.request(),control);
                FeedbackCompressionProperties settings=compression.policy();
                var direct=FeedbackCompression.RERANK_VERSION.equals(job.request().rulesVersion())
                        ?compression.directMaterial(manifest.result(),shared.counts(),parts,control):null;
                var material=direct!=null?boundedMaterial(objects,direct):FeedbackCompression.RERANK_VERSION.equals(job.request().rulesVersion())
                        ?rerank.select(claim,control,()->compression.planRerank(job.owner(),job.request(),manifest.result(),shared.counts(),parts,control,()->store.reserveEmbedding(id,settings.embeddingCalls())),
                                ()->analysis.owned(id,job.owner()),job)
                        :boundedMaterial(objects,compression.compress(id,job.owner(),job.request(),manifest.result(),shared.counts(),parts,control,()->store.reserveEmbedding(id,settings.embeddingCalls())));
                result=objects.put(id,material,control);
                com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("analysis.raw_records",shared.counts().total(),"analysis.filtered_records",material.excluded().size(),"analysis.exact_expressions",material.exactExpressions(),"analysis.clusters",material.clusters().size(),"analysis.representatives",material.representativeCount(),"analysis.representative_bytes",material.modelBytes(),"analysis.unclassified_records",material.unclassified().size()));
            }
            else {
                var manifest=store.get(id,Step.SNAPSHOT);if(manifest==null || !manifest.state().equals("COMPLETE"))throw new AgentException("ANALYSIS_NOT_READY","Shared snapshot is not ready");
                Source source=Source.valueOf(step.name());var rows=new ArrayList<Unit>();
                var partition=snapshots.read(manifest.result(),job.runId(),job.owner(),job.request(),source,control,rows::addAll);
                if(FeedbackCompression.representativeVersion(job.request().rulesVersion())){
                    result=savePart(id,compression.prepare(source,rows,control));
                }else{
                var organized=clusters.organize(id,job.owner(),job.request(),source,rows,control,()->store.reserveEmbedding(id,512));
                var collector=planner.collector(id,job.request(),organized.membership());collector.accept(organized.ordered());
                var shared=snapshots.resolve(manifest.result(),job.runId(),job.owner(),job.request(),control);
                var planned=collector.finish(new FeedbackSnapshotPort.Receipt(shared.dataAsOf(),partition.counts()));
                result=objects.put(id,new Prepared(source,manifest.result(),planned,organized.exactExpressions(),organized.clusters(),organized.method()));
                }
            }
            control.checkpoint();analysis.owned(id,job.owner());
            if(!Instant.now().isBefore(job.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Preparation deadline exceeded");
            if(!store.complete(claim,result,FeedbackPreparationStore.Reference.current()))
                throw new AgentException("PREPARATION_CLAIM_LOST","Preparation result was not accepted");
            return true;
        }catch(AgentException failure){
            var latest=analysis.forJob(id);
            boolean expiredLease=claim!=null && !Instant.now().isBefore(claim.leaseUntil()) && latest!=null && Instant.now().isBefore(latest.deadline());
            if(expiredLease || failure.code().equals("PREPARATION_CLAIM_LOST") || failure.code().equals("ANALYSIS_LEASE_LOST")){
                settle(id);throw new AgentException("PREPARATION_CLAIM_LOST","Preparation result was not accepted");
            }
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
