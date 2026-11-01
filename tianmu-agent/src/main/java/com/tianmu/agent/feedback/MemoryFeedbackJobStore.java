package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import java.time.*;
import java.util.*;

/** Demo/test implementation of the same fenced protocol used by the SQL store. */
public final class MemoryFeedbackJobStore implements FeedbackJobStore {
    private final Map<UUID,Job> jobs = new LinkedHashMap<>();
    private final Map<UUID,Work> work = new LinkedHashMap<>();
    private final Map<UUID,Instant> available = new HashMap<>();
    private final Map<String,Long> reservations = new HashMap<>();
    private final Set<String> settled = new HashSet<>();
    public static boolean active(Phase p) { return Set.of(Phase.QUEUED,Phase.SNAPSHOTTING,Phase.ANALYZING,Phase.MERGING,Phase.VALIDATING).contains(p); }
    public synchronized Job create(Job value) {
        return jobs.values().stream().filter(j -> j.runId().equals(value.runId())).findFirst().orElseGet(() -> { jobs.put(value.id(),value);return value; });
    }
    public synchronized Job get(UUID id) { return jobs.get(id); }
    public synchronized Job forRun(UUID id) {return jobs.values().stream().filter(j->j.runId().equals(id)).findFirst().orElse(null);}
    public synchronized Work work(UUID id){return work.get(id);}
    public synchronized List<Job> active(int limit) { return jobs.values().stream().filter(j -> active(j.phase())).limit(limit).toList(); }
    private boolean matches(UUID id,long version) { var j=jobs.get(id);return j!=null && active(j.phase()) && j.version()==version; }
    private Job changed(Job j,Phase p,String error,Snapshot snapshot,com.tianmu.agent.core.VideoContent.Facts video,Blob asset,int completed,long charged,int calls) {
        return new Job(j.id(),j.runId(),j.owner(),j.request(),p,j.createdAt(),j.deadline(),j.tokenLimit(),j.callLimit(),charged,calls,j.model(),j.policyHash(),error,snapshot,video,asset,completed,j.version()+1);
    }
    private void add(UUID job,int level,List<Blob> inputs) {
        for(int i=0;i<inputs.size();i++) { UUID id=UUID.randomUUID();work.put(id,new Work(id,job,level==0?WorkKind.CLASSIFY:WorkKind.MERGE,level,i,inputs.get(i),null,"PENDING",0,0,Instant.MIN)); }
    }
    public synchronized boolean initialize(UUID id,long version,Snapshot snapshot,com.tianmu.agent.core.VideoContent.Facts video,List<Blob> batches) {
        if(!matches(id,version))return false;var j=get(id);add(id,0,batches);jobs.put(id,changed(j,Phase.ANALYZING,null,snapshot,video,null,0,j.chargedTokens(),j.modelCalls()));return true;
    }
    public synchronized Work claim(UUID id,Instant now,Duration lease) {
        var job=get(id);if(job==null || !active(job.phase()) || !now.isBefore(job.deadline()))return null;
        var found=work.values().stream().filter(w->w.jobId().equals(id) && (w.state().equals("PENDING") && !now.isBefore(available.getOrDefault(w.id(),Instant.MIN)) || w.state().equals("RUNNING") && !now.isBefore(w.leaseUntil())))
                .min(Comparator.comparingInt(Work::level).thenComparingInt(Work::ordinal));
        if(found.isEmpty())return null;var w=found.get();var claimed=new Work(w.id(),id,w.kind(),w.level(),w.ordinal(),w.input(),w.result(),"RUNNING",w.fence()+1,w.attempts()+1,now.plus(lease));work.put(w.id(),claimed);return claimed;
    }
    public synchronized boolean complete(Work claim,Blob result) {
        var w=work.get(claim.id());var j=get(claim.jobId());
        if(w==null || j==null || !active(j.phase()) || !Instant.now().isBefore(j.deadline()) || !w.state().equals("RUNNING") || w.fence()!=claim.fence() || !Instant.now().isBefore(w.leaseUntil()))return false;
        work.put(w.id(),new Work(w.id(),w.jobId(),w.kind(),w.level(),w.ordinal(),w.input(),result,"COMPLETE",w.fence(),w.attempts(),w.leaseUntil()));
        jobs.put(j.id(),changed(j,j.phase(),null,j.snapshot(),j.video(),j.asset(),j.completedWork()+1,j.chargedTokens(),j.modelCalls()));return true;
    }
    public synchronized boolean retry(Work claim,String error,Instant at,int max) {
        var w=work.get(claim.id());var j=get(claim.jobId());if(w==null || !active(j.phase()) || !w.state().equals("RUNNING") || w.fence()!=claim.fence())return false;
        if(w.attempts()>=max){fail(j.id(),error);return false;}
        work.put(w.id(),new Work(w.id(),w.jobId(),w.kind(),w.level(),w.ordinal(),w.input(),null,"PENDING",w.fence(),w.attempts(),Instant.MIN));available.put(w.id(),at);return true;
    }
    public synchronized List<Work> completedLevel(UUID id,int level) {
        var rows=work.values().stream().filter(w->w.jobId().equals(id) && w.level()==level).sorted(Comparator.comparingInt(Work::ordinal)).toList();
        return rows.stream().anyMatch(w->!w.state().equals("COMPLETE"))?List.of():rows;
    }
    public synchronized boolean hasLevel(UUID id,int level){return work.values().stream().anyMatch(w->w.jobId().equals(id) && w.level()==level);}
    public synchronized boolean planLevel(UUID id,long version,int level,List<Blob> inputs) {
        if(!matches(id,version) || work.values().stream().anyMatch(w->w.jobId().equals(id) && w.level()==level))return false;
        var j=get(id);add(id,level,inputs);jobs.put(id,changed(j,Phase.MERGING,null,j.snapshot(),j.video(),null,j.completedWork(),j.chargedTokens(),j.modelCalls()));return true;
    }
    public synchronized boolean ready(UUID id,long version,Blob asset) {
        if(!matches(id,version))return false;var j=get(id);jobs.put(id,changed(j,Phase.READY,null,j.snapshot(),j.video(),asset,j.completedWork(),j.chargedTokens(),j.modelCalls()));return true;
    }
    public synchronized boolean phase(UUID id,long version,Phase p) {
        if(!matches(id,version))return false;var j=get(id);jobs.put(id,changed(j,p,null,j.snapshot(),j.video(),j.asset(),j.completedWork(),j.chargedTokens(),j.modelCalls()));return true;
    }
    public synchronized void fail(UUID id,String code) {var j=get(id);if(j!=null && active(j.phase()))jobs.put(id,changed(j,Phase.FAILED,code,j.snapshot(),j.video(),null,j.completedWork(),j.chargedTokens(),j.modelCalls()));}
    public synchronized void cancel(UUID id) {var j=get(id);if(j!=null && (active(j.phase()) || j.phase()==Phase.WAITING_AUTH))jobs.put(id,changed(j,Phase.CANCELLED,"CANCELLED",j.snapshot(),j.video(),null,j.completedWork(),j.chargedTokens(),j.modelCalls()));}
    public synchronized boolean reauthorize(UUID id,com.tianmu.agent.core.AgentTypes.AccessScope caller) {
        var j=get(id);if(j==null || j.phase()!=Phase.WAITING_AUTH || !j.owner().tenantId().equals(caller.tenantId()) || !j.owner().userId().equals(caller.userId()) || !caller.videoIds().contains(j.request().query().videoId()))return false;
        jobs.put(id,new Job(j.id(),j.runId(),new com.tianmu.agent.core.AgentTypes.AccessScope(j.owner().tenantId(),j.owner().userId(),j.owner().videoIds(),caller.authorizationRef()),j.request(),j.snapshot()==null?Phase.QUEUED:Phase.ANALYZING,j.createdAt(),j.deadline(),j.tokenLimit(),j.callLimit(),j.chargedTokens(),j.modelCalls(),j.model(),j.policyHash(),null,j.snapshot(),j.video(),j.asset(),j.completedWork(),j.version()+1));return true;
    }
    public synchronized long reserve(UUID id,UUID workId,long fence,long estimate) {
        var j=get(id);var w=work.get(workId);String key=workId+":"+fence;
        if(j==null || !active(j.phase()) || w==null || !w.jobId().equals(id) || !w.state().equals("RUNNING") || w.fence()!=fence || reservations.containsKey(key))throw new AgentException("ANALYSIS_LEASE_LOST","Work is no longer owned");
        if(estimate<1 || j.modelCalls()>=j.callLimit() || estimate>j.tokenLimit()-j.chargedTokens())throw new AgentException("MODEL_TOKEN_BUDGET_EXCEEDED","Analysis budget exhausted");
        reservations.put(key,estimate);jobs.put(id,changed(j,j.phase(),null,j.snapshot(),j.video(),j.asset(),j.completedWork(),Math.addExact(j.chargedTokens(),estimate),j.modelCalls()+1));return estimate;
    }
    public synchronized void settle(UUID id,UUID workId,long fence,long prompt,long completion) {
        String key=workId+":"+fence;Long reserved=reservations.get(key);if(reserved==null || !settled.add(key))return;
        var j=get(id);long actual=Math.addExact(prompt,completion);if(prompt<0 || completion<0)throw new IllegalArgumentException();
        jobs.put(id,changed(j,j.phase(),j.errorCode(),j.snapshot(),j.video(),j.asset(),j.completedWork(),Math.addExact(j.chargedTokens()-reserved,actual),j.modelCalls()));
    }
}
