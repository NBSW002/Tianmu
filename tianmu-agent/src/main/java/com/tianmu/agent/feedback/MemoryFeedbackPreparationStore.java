package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.Blob;
import java.time.*;
import java.util.*;

public final class MemoryFeedbackPreparationStore implements FeedbackPreparationStore {
    private record Key(UUID job,Step step) {}
    private final Map<Key,Work> work=new HashMap<>();
    private final Map<UUID,Integer> calls=new HashMap<>();
    public synchronized void request(UUID job,Step step){work.putIfAbsent(new Key(job,step),new Work(job,step,"PENDING",0,0,Instant.MIN,null));}
    public synchronized Work get(UUID job,Step step){return work.get(new Key(job,step));}
    public synchronized Work claim(UUID job,Step step,Instant now,Duration lease){
        var w=get(job,step);if(w==null || w.state().equals("COMPLETE") || now.isBefore(w.leaseUntil()))return null;
        if(w.attempts()>=3)throw new AgentException("PREPARATION_RETRY_EXHAUSTED","Source preparation retry limit reached");
        var next=new Work(job,step,"RUNNING",w.fence()+1,w.attempts()+1,now.plus(lease),null);work.put(new Key(job,step),next);return next;
    }
    public synchronized boolean complete(Work claim,Blob result){
        var w=get(claim.jobId(),claim.step());
        if(w==null || !w.state().equals("RUNNING") || w.fence()!=claim.fence() || !Instant.now().isBefore(w.leaseUntil()))return false;
        work.put(new Key(w.jobId(),w.step()),new Work(w.jobId(),w.step(),"COMPLETE",w.fence(),w.attempts(),w.leaseUntil(),result));return true;
    }
    public synchronized void release(Work claim){
        var w=get(claim.jobId(),claim.step());
        if(w!=null && w.fence()==claim.fence() && w.state().equals("RUNNING"))work.put(new Key(w.jobId(),w.step()),
                new Work(w.jobId(),w.step(),"PENDING",w.fence(),w.attempts(),Instant.now().plusSeconds(2L*w.attempts()),null));
    }
    public synchronized void reserveEmbedding(UUID job,int limit){
        int used=calls.getOrDefault(job,0);if(used>=limit)throw new AgentException("EMBEDDING_BUDGET_EXCEEDED","Embedding request budget exhausted");calls.put(job,used+1);
    }
}
