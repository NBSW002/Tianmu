package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.Blob;
import java.time.*;
import java.util.*;

public final class MemoryFeedbackPreparationStore implements FeedbackPreparationStore {
    private record Key(UUID job,Step step) {}
    private final Map<Key,Work> work=new HashMap<>();
    private final Map<UUID,Integer> calls=new HashMap<>();
    private record RerankKey(UUID job,String slot) {}
    private record RerankAttempt(long fence,RerankSlot value) {}
    private final Map<RerankKey,RerankAttempt> rerank=new HashMap<>();
    public synchronized void request(UUID job,Step step){work.putIfAbsent(new Key(job,step),new Work(job,step,"PENDING",0,0,Instant.MIN,null));}
    public synchronized void request(UUID job,Step step,Reference trigger){work.putIfAbsent(new Key(job,step),new Work(job,step,"PENDING",0,0,Instant.MIN,null,null,trigger));}
    public synchronized Work get(UUID job,Step step){return work.get(new Key(job,step));}
    public synchronized Work claim(UUID job,Step step,Instant now,Duration lease){
        var w=get(job,step);if(w==null || !Set.of("PENDING","RUNNING").contains(w.state()) || now.isBefore(w.leaseUntil()))return null;
        if(w.attempts()>=3)throw new AgentException("PREPARATION_RETRY_EXHAUSTED","Source preparation retry limit reached");
        var next=new Work(job,step,"RUNNING",w.fence()+1,w.attempts()+1,now.plus(lease),null,null,w.triggeredBy());work.put(new Key(job,step),next);return next;
    }
    public synchronized boolean complete(Work claim,Blob result){return complete(claim,result,null);}
    public synchronized boolean complete(Work claim,Blob result,Reference acceptedBy){
        var w=get(claim.jobId(),claim.step());
        if(w==null || !w.state().equals("RUNNING") || w.fence()!=claim.fence() || !Instant.now().isBefore(w.leaseUntil()))return false;
        work.put(new Key(w.jobId(),w.step()),new Work(w.jobId(),w.step(),"COMPLETE",w.fence(),w.attempts(),w.leaseUntil(),result,acceptedBy,w.triggeredBy()));return true;
    }
    public synchronized void release(Work claim){
        var w=get(claim.jobId(),claim.step());
        if(w!=null && w.fence()==claim.fence() && w.state().equals("RUNNING"))work.put(new Key(w.jobId(),w.step()),
                new Work(w.jobId(),w.step(),"PENDING",w.fence(),w.attempts(),Instant.now().plusSeconds(2L*w.attempts()),null,null,w.triggeredBy()));
    }
    public synchronized void settle(UUID job,String state){
        if(!Set.of("FAILED","CANCELLED").contains(state))throw new IllegalArgumentException("Expected terminal state");
        work.replaceAll((key,w)->w.jobId().equals(job) && Set.of("PENDING","RUNNING").contains(w.state())
                ?new Work(job,w.step(),state,w.fence()+1,w.attempts(),Instant.MIN,null,null,w.triggeredBy()):w);
    }
    public synchronized void reserveEmbedding(UUID job,int limit){
        int used=calls.getOrDefault(job,0);if(used>=limit)throw new AgentException("EMBEDDING_BUDGET_EXCEEDED","Embedding request budget exhausted");calls.put(job,used+1);
    }
    public synchronized RerankSlot rerankSlot(UUID job,String slot){
        var attempt=rerank.get(new RerankKey(job,slot));return attempt==null?null:attempt.value();
    }
    private void requireRerankLease(Work claim){
        var current=get(claim.jobId(),claim.step());
        if(current==null || !"RUNNING".equals(current.state()) || current.fence()!=claim.fence() || !Instant.now().isBefore(current.leaseUntil()))
            throw new AgentException("ANALYSIS_LEASE_LOST","Rerank work is no longer owned");
    }
    public synchronized boolean reserveRerank(Work claim,String slot,Blob result){
        FeedbackPreparationStore.requireRerankSlot(claim,slot,result!=null);
        requireRerankLease(claim);
        return rerank.putIfAbsent(new RerankKey(claim.jobId(),slot),new RerankAttempt(claim.fence(),new RerankSlot(result)))==null;
    }
    public synchronized void completeRerank(Work claim,String slot,Blob result){
        FeedbackPreparationStore.requireRerankSlot(claim,slot,false);
        requireRerankLease(claim);
        var key=new RerankKey(claim.jobId(),slot);var attempt=rerank.get(key);
        if(attempt==null || attempt.fence()!=claim.fence() || attempt.value().result()!=null)
            throw new AgentException("ANALYSIS_LEASE_LOST","Rerank attempt is no longer writable");
        rerank.put(key,new RerankAttempt(claim.fence(),new RerankSlot(Objects.requireNonNull(result))));
    }
}
