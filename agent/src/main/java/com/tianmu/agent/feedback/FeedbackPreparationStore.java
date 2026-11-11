package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.Blob;
import java.time.*;
import java.util.*;

/** Child work under an existing feedback job; result acceptance is fenced, never last-writer-wins. */
public interface FeedbackPreparationStore {
    enum Step { SNAPSHOT, COMMENT, DANMAKU, MATERIALS, REPORT_JSON }
    record Reference(String traceId,String spanId) {
        public Reference {
            if(traceId==null || !traceId.matches("[0-9a-f]{32}") || traceId.equals("0".repeat(32)) || spanId==null || !spanId.matches("[0-9a-f]{16}") || spanId.equals("0".repeat(16))) throw new IllegalArgumentException("Invalid span reference");
        }
        public static Reference current(){var context=io.opentelemetry.api.trace.Span.current().getSpanContext();return context.isValid()?new Reference(context.getTraceId(),context.getSpanId()):null;}
    }
    record Work(UUID jobId,Step step,String state,long fence,int attempts,Instant leaseUntil,Blob result,Reference acceptedBy,Reference triggeredBy) {
        public Work(UUID jobId,Step step,String state,long fence,int attempts,Instant leaseUntil,Blob result){this(jobId,step,state,fence,attempts,leaseUntil,result,null,null);}
    }
    void request(UUID job,Step step);
    void request(UUID job,Step step,Reference trigger);
    Work get(UUID job,Step step);
    Work claim(UUID job,Step step,Instant now,Duration lease);
    boolean complete(Work claim,Blob result);
    boolean complete(Work claim,Blob result,Reference acceptedBy);
    void release(Work claim);
    /** Fence unfinished children when their parent terminates. Completed evidence remains immutable. */
    void settle(UUID job,String terminalState);
    /** Persisted across retries and process restarts, shared by both source workers. */
    void reserveEmbedding(UUID job,int limit);
    record RerankSlot(Blob result) {}
    RerankSlot rerankSlot(UUID job,String slot);
    /** The plan is written with its result. A bucket starts with an empty result before dispatch. */
    boolean reserveRerank(Work claim,String slot,Blob result);
    void completeRerank(Work claim,String slot,Blob result);
    static void requireRerankSlot(Work claim,String slot,boolean plan){
        if(claim.step()!=Step.MATERIALS || !(plan?"PLAN".equals(slot):slot.matches("B[1-5]")))
            throw new IllegalArgumentException("Invalid rerank slot");
    }
}
