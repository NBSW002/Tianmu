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
                    r.get("result_json")==null?null:json.readValue(r.get("result_json").toString(),Blob.class));
        }catch(Exception invalid){throw new AgentException("STORAGE_CORRUPT","Invalid preparation ledger");}
    }
    public void request(UUID job,Step step){write(()->{lock(job);db.update("INSERT INTO agent_feedback_preparation(job_id,step) VALUES(?,?) ON DUPLICATE KEY UPDATE job_id=job_id",job.toString(),step.name());return null;});}
    public Work get(UUID job,Step step){return database(()->{var rows=db.queryForList("SELECT * FROM agent_feedback_preparation WHERE job_id=? AND step=?",job.toString(),step.name());return rows.isEmpty()?null:row(rows.get(0));});}
    public Work claim(UUID job,Step step,Instant now,Duration lease){return write(()->{
        lock(job);var rows=db.queryForList("SELECT *, (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(6)) available FROM agent_feedback_preparation WHERE job_id=? AND step=? FOR UPDATE",job.toString(),step.name());
        if(rows.isEmpty())return null;var r=rows.get(0);var w=row(r);
        if(!Set.of("PENDING","RUNNING").contains(w.state()) || ((Number)r.get("available")).intValue()!=1)return null;
        if(w.attempts()>=3)throw new AgentException("PREPARATION_RETRY_EXHAUSTED","Source preparation retry limit reached");
        db.update("UPDATE agent_feedback_preparation SET state='RUNNING',fence=fence+1,attempts=attempts+1,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? MICROSECOND) WHERE job_id=? AND step=?",Math.multiplyExact(lease.toMillis(),1000),job.toString(),step.name());return get(job,step);
    });}
    public boolean complete(Work w,Blob result){return write(()->{
        lock(w.jobId());String encoded;try{encoded=json.writeValueAsString(result);}catch(Exception invalid){throw new IllegalArgumentException("Invalid result reference");}
        return db.update("UPDATE agent_feedback_preparation SET state='COMPLETE',result_json=CAST(? AS JSON) WHERE job_id=? AND step=? AND state='RUNNING' AND fence=? AND lease_until>UTC_TIMESTAMP(6)",encoded,w.jobId().toString(),w.step().name(),w.fence())==1;
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
}
