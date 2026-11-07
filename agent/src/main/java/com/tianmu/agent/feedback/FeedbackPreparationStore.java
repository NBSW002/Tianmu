package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.Blob;
import java.time.*;
import java.util.*;

/** Child work under an existing feedback job; result acceptance is fenced, never last-writer-wins. */
public interface FeedbackPreparationStore {
    enum Step { SNAPSHOT, COMMENT, DANMAKU }
    record Work(UUID jobId,Step step,String state,long fence,int attempts,Instant leaseUntil,Blob result) {}
    void request(UUID job,Step step);
    Work get(UUID job,Step step);
    Work claim(UUID job,Step step,Instant now,Duration lease);
    boolean complete(Work claim,Blob result);
    void release(Work claim);
    /** Fence unfinished children when their parent terminates. Completed evidence remains immutable. */
    void settle(UUID job,String terminalState);
    /** Persisted across retries and process restarts, shared by both source workers. */
    void reserveEmbedding(UUID job,int limit);
}
