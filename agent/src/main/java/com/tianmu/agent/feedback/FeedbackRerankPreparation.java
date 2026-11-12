package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.FeedbackAnalysis.WorkKind;
import com.tianmu.agent.feedback.FeedbackCompression.*;
import com.tianmu.agent.feedback.FeedbackPreparationStore.Work;
import com.tianmu.agent.runtime.control.CallControl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import io.opentelemetry.context.Context;

/** Paid bucket selection is part of the existing fenced MATERIALS work. */
final class FeedbackRerankPreparation {
    record Frozen(RerankPlan plan,Instant deadline) {}
    private record BucketResult(List<Candidate> selected,boolean degraded,boolean overBudget) {}
    private static final int REQUEST_TOKEN_RESERVATION=2048;
    private final FeedbackPreparationStore store;
    private final FeedbackObjects objects;
    private final FeedbackReranker reranker;
    private final com.github.benmanes.caffeine.cache.Cache<String,FeedbackReranker.Result> cache;

    FeedbackRerankPreparation(FeedbackPreparationStore store,FeedbackObjects objects,FeedbackReranker reranker,FeedbackReusePolicy reuse){
        this.store=store;this.objects=objects;this.reranker=reranker;
        cache=reuse.enabled() && !reuse.window().isZero()
                ?com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(reuse.maxEntries()).expireAfterWrite(reuse.window()).build()
                :null;
    }

    Material select(Work claim,CallControl control,Supplier<RerankPlan> makePlan,Runnable authorize,FeedbackJobStore.Job job){
        var slot=store.rerankSlot(claim.jobId(),"PLAN");
        if(slot==null){
            var plan=makePlan.get();
            var material=FeedbackPreparationService.boundedMaterial(objects,plan.material());
            var candidate=new Frozen(new RerankPlan(material,plan.buckets()),Instant.now().plusSeconds(5));
            store.reserveRerank(claim,"PLAN",objects.put(claim.jobId(),candidate,control));
            slot=store.rerankSlot(claim.jobId(),"PLAN");
        }
        var frozen=objects.get(slot.result(),Frozen.class);
        var deadline=frozen.deadline().isBefore(control.deadline())?frozen.deadline():control.deadline();
        var clusters=new ArrayList<Cluster>();
        var groups=new ArrayList<FeedbackModels.Group>();
        var material=frozen.plan().material();
        var results=rankBuckets(claim,control,authorize,frozen.plan().buckets(),deadline,job);
        for(int index=0;index<results.size();index++){
            var bucket=frozen.plan().buckets().get(index);
            var selected=results.get(index).selected();
            var sourceCluster=material.clusters().get(index);
            clusters.add(new Cluster(bucket.id(),sourceCluster.members(),selected.stream().map(Candidate::representative).toList()));
            for(var item:selected){
                var rep=item.representative();
                groups.add(new FeedbackModels.Group(rep.id(),rep.source(),rep.text(),rep.context(),false,rep.playbackMs(),
                        item.originalIds().size(),item.originalIds(),bucket.id()));
            }
        }
        var ordered=new ArrayList<FeedbackModels.Group>();
        for(int rank=0;rank<FeedbackReranker.EVIDENCE_PER_BUCKET;rank++){
            for(var cluster:clusters){
                if(cluster.representatives().size()<=rank)continue;
                String id=cluster.representatives().get(rank).id();
                groups.stream().filter(group->group.id().equals(id)).findFirst().ifPresent(ordered::add);
            }
        }
        var kept=new HashSet<>(ordered.stream().map(FeedbackModels.Group::id).toList());
        var evidenceClusters=clusters.stream().map(cluster->new Cluster(cluster.id(),cluster.members(),
                cluster.representatives().stream().filter(rep->kept.contains(rep.id())).toList())).toList();
        long fallbackBuckets=results.stream().filter(BucketResult::degraded).count();
        com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),
                Map.of("analysis.rerank_fallback_buckets",fallbackBuckets,"analysis.rerank_candidates",
                        frozen.plan().buckets().stream().mapToInt(bucket->bucket.candidates().size()).sum(),
                        "analysis.rerank_selected",ordered.size()));
        var input=new FeedbackModels.Input(WorkKind.CLASSIFY,ordered,List.of(),null,null,null,fallbackBuckets>0);
        return FeedbackPreparationService.boundedMaterial(objects,new Material(material.version(),material.policy(),material.protocolRevision(),
                material.manifest(),material.rawCounts(),material.eligibleCounts(),material.excluded(),material.exactExpressions(),
                evidenceClusters,List.of(input),ordered.size(),objects.bytes(ordered.stream().map(FeedbackRerankConfirmation::modelEvidence).toList()),
                material.unclassified(),List.of(),material.bucketMetric(),material.stats()));
    }

    private List<BucketResult> rankBuckets(Work claim,CallControl control,Runnable authorize,List<RerankBucket> buckets,Instant deadline,FeedbackJobStore.Job job){
        var results=new ArrayList<BucketResult>();
        if(buckets.isEmpty())return results;
        var worker=Executors.newFixedThreadPool(2);
        var context=Context.current();
        boolean budgetAvailable=true;
        try{
            for(int first=0;first<buckets.size();first+=2){
                control.checkpoint();
                var wave=new ArrayList<CompletableFuture<BucketResult>>();
                boolean mayDispatch=budgetAvailable;
                for(var bucket:buckets.subList(first,Math.min(first+2,buckets.size()))){
                    wave.add(CompletableFuture.supplyAsync(()->{
                        try(var ignored=context.makeCurrent()){
                            return rankBucket(claim,control,authorize,bucket,deadline,mayDispatch,job);
                        }
                    },worker));
                }
                // Wait for both workers even on failure, so their leases and I/O are not abandoned.
                CompletableFuture.allOf(wave.toArray(CompletableFuture[]::new)).join();
                for(var item:wave){
                    var ranked=item.join();
                    results.add(ranked);
                    budgetAvailable&=!ranked.overBudget();
                }
            }
            return results;
        }catch(CompletionException failed){
            if(failed.getCause() instanceof RuntimeException cause)throw cause;
            throw failed;
        }finally{worker.shutdown();}
    }

    private BucketResult rankBucket(Work claim,CallControl control,Runnable authorize,RerankBucket bucket,Instant deadline,boolean mayDispatch,FeedbackJobStore.Job job){
        control.checkpoint();
        var previous=store.rerankSlot(claim.jobId(),bucket.id());
        var result=previous==null || previous.result()==null?null:objects.get(previous.result(),FeedbackReranker.Result.class);
        var candidates=bucket.candidates();
        var query=promptText(bucket.anchor());
        var documents=boundedDocuments(query,candidates);
        String key=cache!=null && !job.request().forceReanalysis() && documents.size()>=2?cacheKey(job,query,documents):null;
        if(result==null && previous==null && key!=null && (result=cache.getIfPresent(key))!=null)authorize.run();
        int minRanked=Math.max(2,Math.min(FeedbackReranker.EVIDENCE_PER_BUCKET,candidates.size()));
        if(result==null && previous==null && mayDispatch && reranker!=null && documents.size()>=minRanked && Instant.now().isBefore(deadline)){
            authorize.run();
            if(store.reserveRerank(claim,bucket.id(),null)){
                var requestControl=new CallControl(deadline,control.cancellation()).withDispatchBarrier(authorize);
                try{result=reranker.rank(query,documents,requestControl);}
                catch(AgentException unavailable){
                    if(!Set.of("RERANK_UNAVAILABLE","RERANK_PROTOCOL_ERROR","RERANK_INPUT_TOO_LARGE","DEADLINE_EXCEEDED").contains(unavailable.code()))throw unavailable;
                }
                if(result!=null){
                    store.completeRerank(claim,bucket.id(),objects.put(claim.jobId(),result,control));
                    if(key!=null && result.inputTokens()!=null && result.inputTokens()<=REQUEST_TOKEN_RESERVATION)cache.put(key,result);
                }
            }
        }
        var pool=result==null?candidates:candidates.subList(0,documents.size());
        var selected=FeedbackReranker.topThree(pool.size(),result).stream().map(pool::get).toList();
        return new BucketResult(selected,result==null && candidates.size()>=2,
                result!=null && result.inputTokens()!=null && result.inputTokens()>REQUEST_TOKEN_RESERVATION);
    }

    private static String cacheKey(FeedbackJobStore.Job job,String query,List<String> documents){
        var parts=new ArrayList<>(List.of(job.owner().tenantId(),job.owner().userId(),job.request().query().toString(),
                job.request().sources().toString(),job.request().grounding().name(),job.policyHash(),query));
        parts.addAll(documents);
        var framed=new StringBuilder();
        for(var part:parts)framed.append(part.length()).append(':').append(part);
        return FeedbackObjects.sha(framed.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> boundedDocuments(String query,List<Candidate> candidates){
        int remaining=REQUEST_TOKEN_RESERVATION-256;
        int perQuery=query.getBytes(StandardCharsets.UTF_8).length+16;
        var documents=new ArrayList<String>();
        for(var candidate:candidates){
            String text=promptText(candidate.representative());
            remaining-=perQuery+text.getBytes(StandardCharsets.UTF_8).length;
            if(remaining<0)break;
            documents.add(text);
        }
        return List.copyOf(documents);
    }

    private static String promptText(Representative representative){
        return representative.context().isBlank()?representative.text():representative.text()+"\n上文："+representative.context();
    }
}
