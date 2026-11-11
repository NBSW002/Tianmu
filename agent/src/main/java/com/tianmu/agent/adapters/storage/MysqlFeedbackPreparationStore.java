package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.Blob;
import com.tianmu.agent.feedback.FeedbackPreparationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

public final class MysqlFeedbackPreparationStore implements FeedbackPreparationStore {
    private final com.tianmu.agent.feedback.FeedbackRuntimeProperties governance;
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;private final Runnable authority;
    public MysqlFeedbackPreparationStore(DataSource source,ObjectMapper json,Runnable authority) { this(source, json, authority, com.tianmu.agent.feedback.FeedbackRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public MysqlFeedbackPreparationStore(DataSource source,ObjectMapper json,Runnable authority, com.tianmu.agent.feedback.FeedbackRuntimeProperties governance) { this.governance=governance;
        this.json=json;this.authority=authority;db=new JdbcTemplate(source);db.setQueryTimeout(governance.queryTimeoutSeconds());
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(governance.transactionTimeoutSeconds());
    }
    private <T>T database(Supplier<T> action){
        try{return com.tianmu.agent.harness.trace.IoTimings.measure(com.tianmu.agent.harness.trace.IoTimings.Kind.FEEDBACK_DATABASE,action);}
        catch(org.springframework.dao.DataAccessException unavailable){throw new AgentException("STORAGE_UNAVAILABLE","Preparation ledger unavailable");}
        catch(org.springframework.transaction.TransactionException unavailable){throw new AgentException("STORAGE_UNAVAILABLE","Preparation transaction unavailable");}
    }
    private <T>T write(Supplier<T> action){return database(()->{authority.run();return tx.execute(s->action.get());});}
    private void lock(UUID job){if(db.queryForList("SELECT job_id FROM agent_feedback_job WHERE job_id=? FOR UPDATE",job.toString()).isEmpty())throw new AgentException("RESULT_NOT_FOUND","Feedback job missing");}
    private Work row(Map<String,Object> r){
        try {
            Object at=r.get("lease_until");Instant lease=at==null?Instant.MIN:at instanceof Timestamp t?t.toInstant():((LocalDateTime)at).toInstant(ZoneOffset.UTC);
            return new Work(UUID.fromString(r.get("job_id").toString()),Step.valueOf(r.get("step").toString()),r.get("state").toString(),
                    ((Number)r.get("fence")).longValue(),((Number)r.get("attempts")).intValue(),lease,
                    r.get("result_json")==null?null:json.readValue(r.get("result_json").toString(),Blob.class),reference(r,"accepted"),reference(r,"trigger"));
        }catch(Exception invalid){throw new AgentException("STORAGE_CORRUPT","Invalid preparation ledger");}
    }
    private Reference reference(Map<String,Object> row,String prefix){Object trace=row.get(prefix+"_trace_id"),span=row.get(prefix+"_span_id");return trace==null || span==null?null:new Reference(trace.toString(),span.toString());}
    public void request(UUID job,Step step){write(()->{lock(job);db.update("INSERT INTO agent_feedback_preparation(job_id,step) VALUES(?,?) ON DUPLICATE KEY UPDATE job_id=job_id",job.toString(),step.name());return null;});}
    public void request(UUID job,Step step,Reference trigger){write(()->{lock(job);db.update("INSERT INTO agent_feedback_preparation(job_id,step,trigger_trace_id,trigger_span_id) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE job_id=job_id",job.toString(),step.name(),trigger==null?null:trigger.traceId(),trigger==null?null:trigger.spanId());return null;});}
    public Work get(UUID job,Step step){return database(()->{var rows=db.queryForList("SELECT * FROM agent_feedback_preparation WHERE job_id=? AND step=?",job.toString(),step.name());return rows.isEmpty()?null:row(rows.get(0));});}
    public Work claim(UUID job,Step step,Instant now,Duration lease){return write(()->{
        lock(job);var rows=db.queryForList("SELECT *, (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6)) available FROM agent_feedback_preparation WHERE job_id=? AND step=? FOR UPDATE",job.toString(),step.name());
        if(rows.isEmpty())return null;var r=rows.get(0);var w=row(r);
        if(!Set.of("PENDING","RUNNING").contains(w.state()) || ((Number)r.get("available")).intValue()!=1)return null;
        if(w.attempts()>=3)throw new AgentException("PREPARATION_RETRY_EXHAUSTED","Source preparation retry limit reached");
        db.update("UPDATE agent_feedback_preparation SET state='RUNNING',fence=fence+1,attempts=attempts+1,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? MICROSECOND) WHERE job_id=? AND step=?",Math.multiplyExact(lease.toMillis(),1000),job.toString(),step.name());return get(job,step);
    });}
    public boolean complete(Work w,Blob result){return complete(w,result,null);}
    public boolean complete(Work w,Blob result,Reference acceptedBy){return write(()->{
        lock(w.jobId());String encoded;try{encoded=json.writeValueAsString(result);}catch(Exception invalid){throw new IllegalArgumentException("Invalid result reference");}
        return db.update("UPDATE agent_feedback_preparation SET state='COMPLETE',result_json=CAST(? AS JSON),accepted_trace_id=?,accepted_span_id=? WHERE job_id=? AND step=? AND state='RUNNING' AND fence=? AND lease_until>UTC_TIMESTAMP(6)",encoded,acceptedBy==null?null:acceptedBy.traceId(),acceptedBy==null?null:acceptedBy.spanId(),w.jobId().toString(),w.step().name(),w.fence())==1;
    });}
    public void release(Work w){write(()->{db.update("UPDATE agent_feedback_preparation SET state='PENDING',lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND) WHERE job_id=? AND step=? AND fence=? AND state='RUNNING'",2*w.attempts(),w.jobId().toString(),w.step().name(),w.fence());return null;});}
    public void settle(UUID job,String state){
        if(!Set.of("FAILED","CANCELLED").contains(state))throw new IllegalArgumentException("Expected terminal state");
        write(()->{lock(job);db.update("UPDATE agent_feedback_preparation SET state=?,fence=fence+1,lease_until=NULL WHERE job_id=? AND state IN ('PENDING','RUNNING')",state,job.toString());return null;});
    }
    public void reserveEmbedding(UUID job,int limit){write(()->{
        lock(job);int changed=db.update("UPDATE agent_feedback_preparation SET embedding_calls=embedding_calls+1 WHERE job_id=? AND step='SNAPSHOT' AND embedding_calls<?",job.toString(),limit);
        if(changed!=1)throw new AgentException("EMBEDDING_BUDGET_EXCEEDED","Embedding request budget exhausted");return null;
    });}
    public RerankSlot rerankSlot(UUID job,String slot){return database(()->{
        var rows=db.queryForList("SELECT result_json FROM agent_feedback_rerank_slot WHERE job_id=? AND slot=?",job.toString(),slot);
        if(rows.isEmpty())return null;
        Object value=rows.get(0).get("result_json");
        try{return new RerankSlot(value==null?null:json.readValue(value.toString(),Blob.class));}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new AgentException("STORAGE_CORRUPT","Invalid rerank slot");}
    });}
    private String rerankReference(Blob result){
        try{return result==null?null:json.writeValueAsString(result);}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalArgumentException("Invalid rerank reference");}
    }
    private void requireRerankLease(Work claim){
        lock(claim.jobId());
        if(db.queryForList("""
                SELECT p.job_id FROM agent_feedback_preparation p JOIN agent_feedback_job j ON p.job_id=j.job_id
                WHERE p.job_id=? AND p.step='MATERIALS' AND p.fence=? AND p.state='RUNNING'
                  AND p.lease_until>UTC_TIMESTAMP(6) AND j.deadline>UTC_TIMESTAMP(6)
                  AND j.phase IN ('QUEUED','SNAPSHOTTING','ANALYZING')
                FOR UPDATE
                """,claim.jobId().toString(),claim.fence()).isEmpty())
            throw new AgentException("ANALYSIS_LEASE_LOST","Rerank work is no longer owned");
    }
    public boolean reserveRerank(Work claim,String slot,Blob result){
        FeedbackPreparationStore.requireRerankSlot(claim,slot,result!=null);
        return write(()->{
            requireRerankLease(claim);
            if(rerankSlot(claim.jobId(),slot)!=null)return false;
            db.update("INSERT INTO agent_feedback_rerank_slot(job_id,slot,fence,result_json) VALUES(?,?,?,CAST(? AS JSON))",
                    claim.jobId().toString(),slot,claim.fence(),rerankReference(result));
            return true;
        });
    }
    public void completeRerank(Work claim,String slot,Blob result){
        FeedbackPreparationStore.requireRerankSlot(claim,slot,false);
        write(()->{
            requireRerankLease(claim);
            if(db.update("UPDATE agent_feedback_rerank_slot SET result_json=CAST(? AS JSON) WHERE job_id=? AND slot=? AND fence=? AND result_json IS NULL",
                    rerankReference(Objects.requireNonNull(result)),claim.jobId().toString(),slot,claim.fence())!=1)
                throw new AgentException("ANALYSIS_LEASE_LOST","Rerank attempt is no longer writable");
            return null;
        });
    }
}
