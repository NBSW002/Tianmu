package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.core.VideoContent;
import java.time.*;
import java.util.*;

/** Durable single-writer job/work ledger. Every accepting write is fenced by the claimed work version. */
public interface FeedbackJobStore {
    record Job(UUID id, UUID runId, AccessScope owner, Request request, Phase phase, Instant createdAt,
               Instant deadline, long tokenLimit, int callLimit, long chargedTokens, int modelCalls,
               String model, String policyHash, String errorCode, Snapshot snapshot, VideoContent.Facts video,
               Blob asset, int completedWork, long version) {}
    record Work(UUID id, UUID jobId, WorkKind kind, int level, int ordinal, Blob input, Blob result,
                String state, long fence, int attempts, Instant leaseUntil) {}
    Job create(Job proposed);
    Job get(UUID id);
    Job forRun(UUID run);
    List<Job> active(int limit);
    boolean initialize(UUID id, long version, Snapshot snapshot, VideoContent.Facts video, List<Blob> batches);
    Work claim(UUID job, Instant now, Duration lease);
    Work work(UUID id);
    boolean complete(Work claim, Blob result);
    default boolean retry(Work claim,String error,Instant availableAt,int maximumAttempts){return retry(claim,error,availableAt,maximumAttempts,null);}
    boolean retry(Work claim,String error,Instant availableAt,int maximumAttempts,Blob correctedInput);
    List<Work> completedLevel(UUID job, int level);
    List<Work> completedBefore(UUID job, int ordinal);
    boolean hasLevel(UUID job, int level);
    boolean planLevel(UUID job, long version, int level, List<Blob> inputs);
    boolean ready(UUID job, long version, Blob asset);
    void fail(UUID id, String code);
    void cancel(UUID id);
    boolean phase(UUID id, long version, Phase phase);
    boolean reauthorize(UUID id, AccessScope caller);
    long reserve(UUID id, UUID work, long fence, long estimate);
    void settle(UUID id, UUID work, long fence, long prompt, long completion);
    default boolean durable() { return false; }
}
