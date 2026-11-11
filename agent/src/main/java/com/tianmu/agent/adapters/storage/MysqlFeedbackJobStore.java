package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackJobStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** SQL is the progress and spending authority. Payloads stay in bounded immutable private objects. */
public final class MysqlFeedbackJobStore implements FeedbackJobStore {
    private final com.tianmu.agent.feedback.FeedbackRuntimeProperties governance;
    private static final String ACTIVE="('QUEUED','SNAPSHOTTING','ANALYZING','MERGING','VALIDATING')";
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Runnable authority;
    public MysqlFeedbackJobStore(DataSource source,ObjectMapper mapper) {this(source,mapper,()->{},com.tianmu.agent.feedback.FeedbackRuntimeProperties.defaults());}
    public MysqlFeedbackJobStore(DataSource source,ObjectMapper mapper,Runnable authority) {this(source,mapper,authority,com.tianmu.agent.feedback.FeedbackRuntimeProperties.defaults());}
    public MysqlFeedbackJobStore(DataSource source,ObjectMapper mapper,Runnable authority,com.tianmu.agent.feedback.FeedbackRuntimeProperties governance) {this.governance=governance;
        this.authority=Objects.requireNonNull(authority);
        db=new JdbcTemplate(source);db.setQueryTimeout(governance.queryTimeoutSeconds());tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(governance.transactionTimeoutSeconds());
        json=mapper.copy().findAndRegisterModules();
    }
    private <T> T timed(Supplier<T> call){return com.tianmu.agent.harness.trace.IoTimings.measure(com.tianmu.agent.harness.trace.IoTimings.Kind.FEEDBACK_DATABASE,call);}
    private <T> T write(Supplier<T> call) { return timed(()->{authority.run();return tx.execute(s->call.get());}); }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid analysis storage payload",e);} }
    private <T> T decode(Object value,Class<T> type) { if(value==null)return null;try{return json.readValue(value.toString(),type);}catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid analysis record");} }
    private static Timestamp stamp(Instant t) { return Timestamp.from(t); }
    private static Instant instant(Object value){if(value==null)return Instant.MIN;if(value instanceof Timestamp t)return t.toInstant();if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);throw new AgentException("STORAGE_CORRUPT","Invalid analysis lease time");}
    private static long n(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    private Job job(Map<String,Object> row) {
        Job saved=decode(row.get("job_json"),Job.class);
        return new Job(saved.id(),saved.runId(),saved.owner(),saved.request(),Phase.valueOf(row.get("phase").toString()),saved.createdAt(),saved.deadline(),
                n(row,"token_limit"),(int)n(row,"call_limit"),n(row,"charged_tokens"),(int)n(row,"model_calls"),saved.model(),saved.policyHash(),(String)row.get("error_code"),
                saved.snapshot(),saved.video(),decode(row.get("asset_json"),Blob.class),(int)n(row,"completed_work"),n(row,"version"));
    }
    private Work work(Map<String,Object> row) {
        return new Work(UUID.fromString(row.get("work_id").toString()),UUID.fromString(row.get("job_id").toString()),WorkKind.valueOf(row.get("kind").toString()),
                (int)n(row,"level_no"),(int)n(row,"ordinal_no"),decode(row.get("input_json"),Blob.class),decode(row.get("result_json"),Blob.class),row.get("state").toString(),
                n(row,"fence"),(int)n(row,"attempts"),instant(row.get("lease_until")));
    }
    public Job get(UUID id) {var rows=db.queryForList("SELECT j.* FROM agent_feedback_job j JOIN agent_run r ON r.run_id=j.run_id JOIN agent_session s ON s.session_id=r.session_id AND s.tenant_id=r.tenant_id WHERE j.job_id=? AND r.data_expires_at>UTC_TIMESTAMP(6) AND s.status='OPEN' AND s.expires_at>UTC_TIMESTAMP(6)",id.toString());return rows.isEmpty()?null:job(rows.get(0));}
    public Job forRun(UUID run) {var rows=db.queryForList("SELECT * FROM agent_feedback_job WHERE run_id=?",run.toString());return rows.isEmpty()?null:job(rows.get(0));}
    public Work work(UUID id){return timed(()->{var rows=db.queryForList("SELECT * FROM agent_feedback_work WHERE work_id=?",id.toString());return rows.isEmpty()?null:work(rows.get(0));});}
    private Job locked(UUID id) {var rows=db.queryForList("SELECT * FROM agent_feedback_job WHERE job_id=? FOR UPDATE",id.toString());return rows.isEmpty()?null:job(rows.get(0));}
    private boolean running(Job j) {return j!=null && com.tianmu.agent.feedback.MemoryFeedbackJobStore.active(j.phase());}
    public Job create(Job proposed) {
        return write(()->{
            db.queryForList("SELECT guard_id FROM agent_feedback_guard WHERE guard_id=1 FOR UPDATE");
            var existing=db.queryForList("SELECT * FROM agent_feedback_job WHERE run_id=?",proposed.runId().toString());
            if(!existing.isEmpty())return job(existing.get(0));
            if(db.queryForObject("SELECT COUNT(*) FROM agent_feedback_job WHERE phase IN "+ACTIVE,Long.class)>=32
                    || db.queryForObject("SELECT COUNT(*) FROM agent_feedback_job WHERE tenant_id=? AND user_id=? AND phase IN "+ACTIVE,Long.class,proposed.owner().tenantId(),proposed.owner().userId())>=2)
                throw new AgentException("CAPACITY_EXCEEDED","Analysis queue is full");
            db.update("INSERT INTO agent_feedback_job(job_id,run_id,tenant_id,user_id,phase,job_json,token_limit,call_limit,created_at,deadline) VALUES(?,?,?,?,?,CAST(? AS JSON),?,?,?,?)",
                    proposed.id().toString(),proposed.runId().toString(),proposed.owner().tenantId(),proposed.owner().userId(),proposed.phase().name(),encode(proposed),proposed.tokenLimit(),proposed.callLimit(),stamp(proposed.createdAt()),stamp(proposed.deadline()));
            return get(proposed.id());
        });
    }
    public List<Job> active(int limit) {if(limit<1 || limit>64)throw new IllegalArgumentException();return db.queryForList("SELECT * FROM agent_feedback_job WHERE phase IN "+ACTIVE+" ORDER BY created_at LIMIT ?",limit).stream().map(this::job).toList();}
    private void add(UUID job,int level,List<Blob> inputs) {
        if(inputs.size()>4096)throw new AgentException("FULL_ANALYSIS_TOO_LARGE","Too many analysis work units");
        for(int i=0;i<inputs.size();i++)db.update("INSERT INTO agent_feedback_work(work_id,job_id,kind,level_no,ordinal_no,input_json,state,available_at) VALUES(?,?,?,?,?,CAST(? AS JSON),'PENDING',UTC_TIMESTAMP(6))",
                UUID.randomUUID().toString(),job.toString(),level==0?"CLASSIFY":"MERGE",level,i,encode(inputs.get(i)));
    }
    public boolean initialize(UUID id,long version,Snapshot snapshot,VideoContent.Facts video,List<Blob> batches) {
        return write(()->{
            Job j=locked(id);if(!running(j) || j.version()!=version || j.snapshot()!=null)return false;
            Job saved=new Job(j.id(),j.runId(),j.owner(),j.request(),j.phase(),j.createdAt(),j.deadline(),j.tokenLimit(),j.callLimit(),j.chargedTokens(),j.modelCalls(),j.model(),j.policyHash(),null,snapshot,video,null,j.completedWork(),j.version());
            add(id,0,batches);
            return db.update("UPDATE agent_feedback_job SET phase='ANALYZING',job_json=CAST(? AS JSON),version=version+1 WHERE job_id=? AND version=?",encode(saved),id.toString(),version)==1;
        });
    }
    public Work claim(UUID id,Instant now,Duration lease) {return claim(id,now,lease,1);}
    public Work claim(UUID id,Instant now,Duration lease,int parallelism) {
        if(parallelism<1 || parallelism>4)throw new IllegalArgumentException("Invalid read parallelism");
        return write(()->{
            Job j=locked(id);if(!running(j) || db.queryForObject("SELECT deadline>UTC_TIMESTAMP(6) FROM agent_feedback_job WHERE job_id=?",Integer.class,id.toString())!=1)return null;
            var rows=db.queryForList("SELECT *,((state='PENDING' AND available_at<=UTC_TIMESTAMP(6)) OR (state='RUNNING' AND lease_until<=UTC_TIMESTAMP(6))) claimable FROM agent_feedback_work WHERE job_id=? AND state<>'COMPLETE' ORDER BY level_no,ordinal_no LIMIT 1 FOR UPDATE",id.toString());
            if(rows.isEmpty())return null;Work w=work(rows.get(0));
            boolean seeded=parallelism>1 && w.kind()==WorkKind.CLASSIFY && db.queryForObject("SELECT COUNT(*) FROM agent_feedback_work WHERE job_id=? AND level_no=0 AND ordinal_no=0 AND state='COMPLETE'",Long.class,id.toString())==1;
            if(seeded){
                if(db.queryForObject("SELECT COUNT(*) FROM agent_feedback_work WHERE job_id=? AND kind='CLASSIFY' AND state='RUNNING' AND lease_until>UTC_TIMESTAMP(6)",Long.class,id.toString())>=parallelism)return null;
                rows=db.queryForList("SELECT * FROM agent_feedback_work WHERE job_id=? AND kind='CLASSIFY' AND ((state='PENDING' AND available_at<=UTC_TIMESTAMP(6)) OR (state='RUNNING' AND lease_until<=UTC_TIMESTAMP(6))) ORDER BY ordinal_no LIMIT 1 FOR UPDATE",id.toString());if(rows.isEmpty())return null;w=work(rows.get(0));
            }else if(((Number)rows.get(0).get("claimable")).intValue()!=1)return null;
            db.update("UPDATE agent_feedback_work SET state='RUNNING',fence=fence+1,attempts=attempts+1,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? MICROSECOND) WHERE work_id=?",Math.multiplyExact(lease.toMillis(),1000),w.id().toString());
            return work(db.queryForMap("SELECT * FROM agent_feedback_work WHERE work_id=?",w.id().toString()));
        });
    }
    public boolean complete(Work w,Blob result) {
        return write(()->{
            Job j=locked(w.jobId());if(!running(j) || !Instant.now().isBefore(j.deadline()))return false;
            int changed=db.update("UPDATE agent_feedback_work SET state='COMPLETE',result_json=CAST(? AS JSON) WHERE work_id=? AND state='RUNNING' AND fence=? AND lease_until>UTC_TIMESTAMP(6)",encode(result),w.id().toString(),w.fence());
            if(changed==1)db.update("UPDATE agent_feedback_job SET completed_work=completed_work+1,version=version+1 WHERE job_id=?",w.jobId().toString());return changed==1;
        });
    }
    public boolean retry(Work w,String error,Instant at,int max,Blob correctedInput) {
        return write(()->{
            Job j=locked(w.jobId());if(!running(j))return false;
            int changed=db.update("UPDATE agent_feedback_work SET state=?,available_at=?,error_code=?,lease_until=NULL,input_json=COALESCE(CAST(? AS JSON),input_json) WHERE work_id=? AND state='RUNNING' AND fence=?",w.attempts()>=max?"FAILED":"PENDING",stamp(at),error,correctedInput==null?null:encode(correctedInput),w.id().toString(),w.fence());
            if(changed==1 && w.attempts()>=max)fail(j.id(),error);return changed==1 && w.attempts()<max;
        });
    }
    public List<Work> completedLevel(UUID id,int level) {
        return timed(()->{var rows=db.queryForList("SELECT * FROM agent_feedback_work WHERE job_id=? AND level_no=? ORDER BY ordinal_no",id.toString(),level).stream().map(this::work).toList();
        return rows.stream().anyMatch(w->!w.state().equals("COMPLETE"))?List.of():rows;});
    }
    public List<Work> completedRange(UUID id,int level,int fromOrdinal,int limit){
        return timed(()->db.queryForList("SELECT * FROM agent_feedback_work WHERE job_id=? AND level_no=? AND ordinal_no>=? AND ordinal_no<? AND state='COMPLETE' ORDER BY ordinal_no",
                id.toString(),level,fromOrdinal,fromOrdinal+limit).stream().map(this::work).toList());
    }
    public boolean hasLevel(UUID id,int level){return db.queryForObject("SELECT COUNT(*) FROM agent_feedback_work WHERE job_id=? AND level_no=?",Long.class,id.toString(),level)>0;}
    public boolean planLevel(UUID id,long version,int level,List<Blob> inputs) {
        return write(()->{
            Job j=locked(id);if(!running(j) || j.version()!=version || db.queryForObject("SELECT COUNT(*) FROM agent_feedback_work WHERE job_id=? AND level_no=?",Long.class,id.toString(),level)>0)return false;
            add(id,level,inputs);return db.update("UPDATE agent_feedback_job SET phase='MERGING',version=version+1 WHERE job_id=? AND version=?",id.toString(),version)==1;
        });
    }
    public boolean ready(UUID id,long version,Blob asset) {
        authority.run();
        return db.update("UPDATE agent_feedback_job SET phase='READY',asset_json=CAST(? AS JSON),version=version+1 WHERE job_id=? AND version=? AND phase IN "+ACTIVE+" AND deadline>UTC_TIMESTAMP(6)",encode(asset),id.toString(),version)==1;
    }
    public boolean phase(UUID id,long version,Phase p) {authority.run();return db.update("UPDATE agent_feedback_job SET phase=?,version=version+1 WHERE job_id=? AND version=? AND phase IN "+ACTIVE,p.name(),id.toString(),version)==1;}
    public boolean transitionClaim(Work claim,Phase phase,String errorCode){
        if(phase!=Phase.WAITING_AUTH && phase!=Phase.FAILED)throw new IllegalArgumentException("Invalid claim transition");
        return write(()->{
            var job=locked(claim.jobId());
            if(!running(job))return false;
            return db.update("""
                    UPDATE agent_feedback_job j SET phase=?,error_code=?,version=version+1
                    WHERE j.job_id=? AND EXISTS (
                        SELECT 1 FROM agent_feedback_work w WHERE w.job_id=j.job_id AND w.work_id=?
                          AND w.fence=? AND w.state='RUNNING' AND w.lease_until>UTC_TIMESTAMP(6)
                    )
                    """,phase.name(),errorCode,claim.jobId().toString(),claim.id().toString(),claim.fence())==1;
        });
    }
    public void fail(UUID id,String code) {authority.run();db.update("UPDATE agent_feedback_job SET phase='FAILED',error_code=?,version=version+1 WHERE job_id=? AND phase IN "+ACTIVE,code,id.toString());}
    public void cancel(UUID id) {authority.run();db.update("UPDATE agent_feedback_job SET phase='CANCELLED',error_code='CANCELLED',version=version+1 WHERE job_id=? AND (phase IN "+ACTIVE+" OR phase='WAITING_AUTH')",id.toString());}
    public boolean reauthorize(UUID id,com.tianmu.agent.core.AgentTypes.AccessScope caller) {
        return write(()->{
            Job j=locked(id);if(j==null || j.phase()!=Phase.WAITING_AUTH || !j.owner().tenantId().equals(caller.tenantId()) || !j.owner().userId().equals(caller.userId()) || !caller.videoIds().contains(j.request().query().videoId()))return false;
            Job saved=new Job(j.id(),j.runId(),new com.tianmu.agent.core.AgentTypes.AccessScope(j.owner().tenantId(),j.owner().userId(),j.owner().videoIds(),caller.authorizationRef(),j.owner().readableVideoIds()),j.request(),j.phase(),j.createdAt(),j.deadline(),j.tokenLimit(),j.callLimit(),j.chargedTokens(),j.modelCalls(),j.model(),j.policyHash(),null,j.snapshot(),j.video(),j.asset(),j.completedWork(),j.version());
            return db.update("UPDATE agent_feedback_job SET job_json=CAST(? AS JSON),phase=?,version=version+1 WHERE job_id=? AND version=?",encode(saved),j.snapshot()==null?"QUEUED":"ANALYZING",id.toString(),j.version())==1;
        });
    }
    public long reserve(UUID id,UUID work,long fence,long estimate) {
        return write(()->{
            Job j=locked(id);if(!running(j) || !Instant.now().isBefore(j.deadline()) || db.queryForObject("SELECT COUNT(*) FROM agent_feedback_work WHERE job_id=? AND work_id=? AND fence=? AND state='RUNNING' AND lease_until>UTC_TIMESTAMP(6)",Long.class,id.toString(),work.toString(),fence)!=1)
                throw new AgentException("ANALYSIS_LEASE_LOST","Analysis work is no longer owned");
            if(com.tianmu.agent.feedback.FeedbackCompression.RERANK_VERSION.equals(j.request().rulesVersion()) && j.modelCalls()>0)
                throw new AgentException("MODEL_DISPATCH_ALREADY_RESERVED","Confirmation dispatch has already been reserved");
            if(estimate<1 || j.modelCalls()>=j.callLimit() || estimate>j.tokenLimit()-j.chargedTokens())throw new AgentException("MODEL_TOKEN_BUDGET_EXCEEDED","Analysis budget exhausted");
            if(db.queryForObject("SELECT COUNT(*) FROM agent_feedback_attempt WHERE work_id=? AND fence=?",Long.class,work.toString(),fence)>0)throw new AgentException("ANALYSIS_LEASE_LOST","Model attempt was already dispatched");
            db.update("INSERT INTO agent_feedback_attempt(work_id,fence,job_id,reserved_tokens,state,created_at) VALUES(?,?,?,?,'UNKNOWN',UTC_TIMESTAMP(6))",work.toString(),fence,id.toString(),estimate);
            db.update("UPDATE agent_feedback_job SET charged_tokens=charged_tokens+?,model_calls=model_calls+1,version=version+1 WHERE job_id=?",estimate,id.toString());return estimate;
        });
    }
    public void settle(UUID id,UUID work,long fence,long prompt,long completion) {
        if(prompt<0 || completion<0)throw new IllegalArgumentException();long actual=Math.addExact(prompt,completion);
        write(()->{
            locked(id);var rows=db.queryForList("SELECT reserved_tokens FROM agent_feedback_attempt WHERE job_id=? AND work_id=? AND fence=? AND state='UNKNOWN' FOR UPDATE",id.toString(),work.toString(),fence);
            if(rows.isEmpty())return null;long reserved=((Number)rows.get(0).get("reserved_tokens")).longValue();
            db.update("UPDATE agent_feedback_attempt SET state='REPORTED',prompt_tokens=?,completion_tokens=? WHERE work_id=? AND fence=?",prompt,completion,work.toString(),fence);
            db.update("UPDATE agent_feedback_job SET charged_tokens=charged_tokens-?+?,version=version+1 WHERE job_id=?",reserved,actual,id.toString());return null;
        });
    }
    public boolean durable(){return true;}
}
