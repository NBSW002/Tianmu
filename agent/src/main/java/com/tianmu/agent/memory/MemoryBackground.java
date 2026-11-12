package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.harness.trace.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** At-least-once outbox -> durable leased jobs. No model call runs on a scheduler or in a SQL transaction. */
@Service @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("(${agent.memory.enabled:false} or ${agent.compaction.enabled:true}) and '${agent.storage.mode:memory}' == 'mysql'")
public final class MemoryBackground {
    private final com.tianmu.agent.memory.MemoryRuntimeProperties governance;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private CompactionService compaction;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private MemoryConsolidation consolidation;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private MemoryDream dream;
    @org.springframework.beans.factory.annotation.Value("${agent.memory.enabled:false}") private boolean memoryEnabled;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MemoryLedger ledger;
    private final ObjectProvider<MemoryModelPort> models;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper json;
    private final Tracer tracer;
    private final TraceDetails details;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicReference<Map<String,Object>> current=new AtomicReference<>();
    public MemoryBackground(DataSource source,MemoryLedger ledger,ObjectProvider<MemoryModelPort> models,@Qualifier("memoryExecutor")ThreadPoolTaskExecutor executor,ObjectMapper json,Tracer tracer,TraceDetails details) { this(source, ledger, models, executor, json, tracer, details, com.tianmu.agent.memory.MemoryRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public MemoryBackground(DataSource source,MemoryLedger ledger,ObjectProvider<MemoryModelPort> models,@Qualifier("memoryExecutor")ThreadPoolTaskExecutor executor,ObjectMapper json,Tracer tracer,TraceDetails details, com.tianmu.agent.memory.MemoryRuntimeProperties governance) { this.governance=governance;db=new JdbcTemplate(source);db.setQueryTimeout(governance.queryTimeoutSeconds());tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(governance.transactionTimeoutSeconds());this.ledger=ledger;this.models=models;this.executor=executor;this.json=json;this.tracer=tracer;this.details=details;}
    /** Caller already owns the run/session transaction. */
    public void enqueue(JdbcTemplate transaction,AccessScope owner,UUID session,UUID run){
        if(compaction!=null)compaction.enqueue(transaction,owner,session,run);
        if(!memoryEnabled)return;
        var model=models.getIfAvailable();if(model==null)return;
        if(consolidation!=null)consolidation.enqueue(transaction,owner,session,run,model.version());
    }

    public void accept(Map<String,Object> event){
        var payload=decode(event.get("payload_json"));
        if("MEMORY_COMPACT_CHECK".equals(event.get("destination"))){
            if(compaction==null)throw new AgentException("MEMORY_DISABLED","Compaction worker is disabled");
            var check=new LinkedHashMap<>(event);check.put("consent_epoch",payload.path("consent").asLong());check.put("erase_epoch",payload.path("erase").asLong());compaction.catchUp(check);return;
        }
        boolean compact="MEMORY_COMPACT".equals(event.get("destination"));boolean consolidate="MEMORY_CONSOLIDATE".equals(event.get("destination"));
        if(!compact && !consolidate)return; // Retired single-message events cannot recreate obsolete jobs.
        if(consolidate){
            int explicit=payload.path("explicit").asBoolean(false)?1:0;
            Integer live=db.queryForObject("SELECT COUNT(*) FROM agent_memory_window w JOIN agent_memory_observation ob ON ob.tenant_id=w.tenant_id AND ob.owner_user_id=w.owner_user_id AND ob.run_id=w.run_id JOIN agent_memory_account a ON a.tenant_id=w.tenant_id AND a.owner_user_id=w.owner_user_id JOIN agent_run r ON r.tenant_id=w.tenant_id AND r.owner_user_id=w.owner_user_id AND r.run_id=w.run_id JOIN agent_session ss ON ss.tenant_id=w.tenant_id AND ss.owner_user_id=w.owner_user_id AND ss.session_id=w.session_id WHERE w.tenant_id=? AND w.owner_user_id=? AND w.run_id=? AND w.state='PENDING' AND w.explicit_request=? AND ob.eligible=1 AND a.mode='ON' AND (a.auto_extract=1 OR ?=1) AND a.consent_epoch=? AND a.erase_epoch=? AND ob.consent_epoch=a.consent_epoch AND ob.erase_epoch=a.erase_epoch AND ss.status='OPEN' AND ss.expires_at>UTC_TIMESTAMP(6) AND r.data_expires_at>UTC_TIMESTAMP(6)",Integer.class,event.get("tenant_id"),event.get("owner_user_id"),event.get("run_id"),explicit,explicit,payload.path("consent").asLong(),payload.path("erase").asLong());
            if(live==null || live!=1)return;
        }
        String generation=compact?com.tianmu.agent.adapters.storage.FileObjectStore.hash((payload.path("fingerprint").asText()+":"+payload.path("consent").asLong()+":"+payload.path("erase").asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8)):consolidate?MemoryConsolidation.hash(payload.path("policy").asText()+":"+payload.path("explicit").asBoolean()+":"+payload.path("consent").asLong()+":"+payload.path("erase").asLong()):"";
        db.update("INSERT INTO agent_memory_job(job_id,tenant_id,owner_user_id,session_id,run_id,consent_epoch,erase_epoch,model_version,trace_parent,job_type,plan_json,generation_key,available_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE job_id=job_id",UUID.randomUUID().toString(),event.get("tenant_id"),event.get("owner_user_id"),event.get("session_id"),event.get("run_id"),payload.path("consent").asLong(),payload.path("erase").asLong(),payload.path("model").asText(),payload.path("traceparent").asText(null),compact?"COMPACT":consolidate?"CONSOLIDATE":"EXTRACT",payload.toString(),generation);
        if(consolidate)db.update("UPDATE agent_memory_job j JOIN agent_memory_window w ON w.tenant_id=j.tenant_id AND w.owner_user_id=j.owner_user_id AND w.run_id=j.run_id SET j.available_at=w.available_at WHERE j.tenant_id=? AND j.owner_user_id=? AND j.run_id=? AND j.job_type='CONSOLIDATE' AND j.state='PENDING'",event.get("tenant_id"),event.get("owner_user_id"),event.get("run_id"));
    }
    public void observe(JdbcTemplate transaction,AccessScope owner,UUID session,UUID run){if(consolidation!=null)consolidation.observe(transaction,owner,session,run);}
    private JsonNode decode(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid memory job payload");}}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelayString="${agent.memory.runtime.poll-millis:2000}")
    public void tick(){if(!busy.compareAndSet(false,true))return;try{executor.execute(()->{try{var availableModel=models.getIfAvailable();if(consolidation!=null && availableModel!=null)consolidation.recover(availableModel.version());if(dream!=null && availableModel!=null)dream.schedule(availableModel.version());var job=claim();if(job!=null){current.set(job);work(job);}}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(MemoryBackground.class).warn("Memory maintenance could not run: {}",failure.getClass().getSimpleName());}finally{current.set(null);busy.set(false);}});}catch(java.util.concurrent.RejectedExecutionException e){busy.set(false);}}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelayString="${agent.memory.runtime.heartbeat-millis:10000}")
    public void heartbeat(){var job=current.get();if(job!=null)db.update("UPDATE agent_memory_job SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND) WHERE job_id=? AND state='RUNNING' AND lease_token=?",governance.leaseSeconds(),job.get("job_id"),job.get("lease_token"));}
    private Map<String,Object> claim(){
        // Reclaim in its own transaction, before account locks; keep publication's lock order.
        db.update("UPDATE agent_memory_job SET state=IF(attempts>=?,'DEAD','PENDING'),lease_token=NULL,lease_until=NULL WHERE state='RUNNING' AND lease_until<UTC_TIMESTAMP(6)",governance.expiredMaxAttempts());
        return tx.execute(status->{
            var rows=db.queryForList("SELECT * FROM agent_memory_job WHERE state='PENDING' AND available_at<=UTC_TIMESTAMP(6) AND EXISTS(SELECT 1 FROM agent_memory_account a WHERE a.tenant_id=agent_memory_job.tenant_id AND a.owner_user_id=agent_memory_job.owner_user_id) AND (job_type<>'CONSOLIDATE' OR NOT EXISTS(SELECT 1 FROM agent_memory_window w WHERE w.tenant_id=agent_memory_job.tenant_id AND w.owner_user_id=agent_memory_job.owner_user_id AND w.session_id=agent_memory_job.session_id AND w.run_id=agent_memory_job.run_id AND w.state='PENDING' AND w.available_at>UTC_TIMESTAMP(6))) AND NOT EXISTS(SELECT 1 FROM agent_memory_job active WHERE active.tenant_id=agent_memory_job.tenant_id AND active.owner_user_id=agent_memory_job.owner_user_id AND active.state='RUNNING') ORDER BY available_at,job_id LIMIT 1");
            if(rows.isEmpty())return null;var job=rows.get(0);
            if(db.queryForList("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE SKIP LOCKED",job.get("tenant_id"),job.get("owner_user_id")).isEmpty())return null;
            // A current read is required after acquiring the account lock: under MySQL RR,
            // an earlier queue peek may have established a snapshot before another claimant committed.
            if(!db.queryForList("SELECT job_id FROM agent_memory_job WHERE tenant_id=? AND owner_user_id=? AND state='RUNNING' FOR UPDATE",job.get("tenant_id"),job.get("owner_user_id")).isEmpty())return null;
            if(db.queryForList("SELECT job_id FROM agent_memory_job WHERE job_id=? AND state='PENDING' FOR UPDATE SKIP LOCKED",job.get("job_id")).isEmpty())return null;
            String token=UUID.randomUUID().toString();db.update("UPDATE agent_memory_job SET state='RUNNING',attempts=attempts+1,lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND) WHERE job_id=?",token,governance.leaseSeconds(),job.get("job_id"));job.put("lease_token",token);job.put("attempts",((Number)job.get("attempts")).intValue()+1);return job;
        });
    }
    private void finish(Map<String,Object> job,String state,String code){db.update("UPDATE agent_memory_job SET state=?,error_code=?,lease_token=NULL,lease_until=NULL,available_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND) WHERE job_id=? AND state='RUNNING' AND lease_token=?",state,code,governance.retryDelaySeconds(),job.get("job_id"),job.get("lease_token"));}
    private void finishCompaction(Map<String,Object> job){tx.executeWithoutResult(status->{
        if(db.update("UPDATE agent_memory_job SET state='SUCCEEDED',error_code=NULL,lease_token=NULL,lease_until=NULL WHERE job_id=? AND state='RUNNING' AND lease_token=?",job.get("job_id"),job.get("lease_token"))!=1)throw new AgentException("STORAGE_FENCED","Compaction completion lost its lease");
        db.update("INSERT INTO agent_outbox(tenant_id,outbox_id,event_id,destination,owner_user_id,session_id,run_id,payload_json) VALUES(?,?,?,'MEMORY_COMPACT_CHECK',?,?,?,?)",job.get("tenant_id"),UUID.randomUUID().toString(),UUID.randomUUID().toString(),job.get("owner_user_id"),job.get("session_id"),job.get("run_id"),ledger.encode(Map.of("consent",job.get("consent_epoch"),"erase",job.get("erase_epoch"))));
    });}
    private void work(Map<String,Object> job){
        boolean compact="COMPACT".equals(job.get("job_type"));boolean dreaming="DREAM".equals(job.get("job_type"));
        var builder=tracer.spanBuilder(compact?"agent.context.compact":dreaming?"agent.memory.dream":"agent.memory.extract").setNoParent();Object parent=job.get("trace_parent");
        if(parent instanceof String p && p.matches("00-[a-f0-9]{32}-[a-f0-9]{16}-[a-f0-9]{2}"))builder.addLink(SpanContext.createFromRemoteParent(p.substring(3,35),p.substring(36,52),TraceFlags.fromHex(p,53),TraceState.getDefault()));
        var span=builder.startSpan();TraceNode.begin(span,compact?"agent.context.compact":dreaming?"agent.memory.dream":"agent.memory.extract");String tenant=job.get("tenant_id").toString(),owner=job.get("owner_user_id").toString();UUID run=UUID.fromString(job.get("run_id").toString()),session=UUID.fromString(job.get("session_id").toString());
        db.update("UPDATE agent_memory_job SET trace_id=? WHERE job_id=? AND lease_token=?",span.getSpanContext().getTraceId(),job.get("job_id"),job.get("lease_token"));
        try(var active=span.makeCurrent();var bound=details.bind(Context.current(),run).with(TraceDetails.SOURCE_JOB,job.get("job_id").toString()).makeCurrent()){
            TraceDetails.protectMemory();TraceAttributes.apply(span,Map.of("run.id",run.toString(),"session.id",session.toString(),"tenant.id",tenant,"actor.id",owner,"span.type",dreaming?"memory_dream":"memory_extract","memory.job_id",job.get("job_id").toString()));
            var model=models.getIfAvailable();
            if(compact){
                if(compaction==null || model==null)throw new AgentException("MODEL_REVISION_UNAVAILABLE","Compaction model is unavailable");
                TraceAttributes.apply(span,Map.of("span.type","context_compact"));
                compaction.generate(job,model,()->{if(db.queryForList("SELECT job_id FROM agent_memory_job WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",job.get("job_id"),job.get("lease_token")).isEmpty())throw new AgentException("STORAGE_FENCED","Compaction lease expired");});
                finishCompaction(job);TraceNode.success(span);span.setStatus(StatusCode.OK);return;
            }
            if(!memoryEnabled){finish(job,"CANCELLED","MEMORY_DISABLED");TraceAttributes.apply(span,Map.of("node.status","CANCELLED"));return;}
            if(model==null || !model.version().equals(job.get("model_version")))throw new AgentException("MODEL_REVISION_UNAVAILABLE","Memory model revision changed");
            if(dreaming){if(dream==null)throw new AgentException("MEMORY_DISABLED","Dream worker disabled");dream.work(job,model);TraceNode.success(span);span.setStatus(StatusCode.OK);return;}
            if("CONSOLIDATE".equals(job.get("job_type"))){
                if(consolidation==null)throw new AgentException("MEMORY_DISABLED","Consolidation is disabled");
                consolidation.work(job,model);TraceNode.success(span);span.setStatus(StatusCode.OK);return;
            }
            throw new AgentException("MEMORY_JOB_OBSOLETE","Unsupported obsolete extraction job");
        }catch(Exception e){
            String code=e instanceof AgentException a?a.code():e instanceof org.springframework.dao.DataAccessException?"STORAGE_UNAVAILABLE":"MEMORY_EXTRACTION_FAILED";
            boolean retry=Set.of("MODEL_UNAVAILABLE","MODEL_CALL_TIMEOUT","MODEL_ERROR","MEMORY_UNAVAILABLE","STORAGE_UNAVAILABLE").contains(code) && ((Number)job.get("attempts")).intValue()<governance.failureMaxAttempts();
            if(consolidation!=null && "CONSOLIDATE".equals(job.get("job_type")) && Set.of("CANDIDATE_CAPACITY","INVALID_MODEL_RESPONSE").contains(code) && consolidation.reduceBatch(job)){}
            else if(consolidation!=null && "CONSOLIDATE".equals(job.get("job_type")) && code.equals("MEMORY_WINDOW_CHANGED"))consolidation.replan(job);
            else if(consolidation!=null && "CONSOLIDATE".equals(job.get("job_type")) && Set.of("MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE").contains(code))consolidation.cancel(job,code);
            else finish(job,retry?"PENDING":Set.of("MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE","MEMORY_DREAM_STALE").contains(code)?"CANCELLED":"DEAD",code);
            span.setStatus(StatusCode.ERROR,code);
            TraceAttributes.apply(span,Map.of("node.status",Set.of("MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE").contains(code)?"CANCELLED":"FAILED","error.code",code));
        }finally{span.end();}
    }
    public List<Map<String,Object>> jobs(AccessScope owner,UUID run){return db.queryForList("SELECT DISTINCT j.job_id,j.run_id,j.job_type,j.state,j.attempts,j.model_calls,j.model_version,j.error_code,j.trace_id,j.created_at,w.decision_counts FROM agent_memory_job j LEFT JOIN agent_memory_window w ON w.tenant_id=j.tenant_id AND w.owner_user_id=j.owner_user_id AND w.job_id=j.job_id WHERE j.tenant_id=? AND j.owner_user_id=? AND (j.run_id=? OR w.run_id=?) ORDER BY j.created_at LIMIT 20",owner.tenantId(),owner.userId(),run.toString(),run.toString());}
    public Map<String,Object> retry(AccessScope owner,UUID run,UUID id,String key){
        PersonalMemory.key(key);ledger.verifyOriginResources(owner,run);
        return tx.execute(status->{
            if(db.queryForList("SELECT session_id FROM agent_session WHERE tenant_id=? AND owner_user_id=? AND status='OPEN' AND expires_at>UTC_TIMESTAMP(6) AND session_id=(SELECT session_id FROM agent_run WHERE tenant_id=? AND run_id=?) FOR UPDATE",owner.tenantId(),owner.userId(),owner.tenantId(),run.toString()).isEmpty())throw MemoryLedger.invalidated();
            var accounts=db.queryForList("SELECT * FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",owner.tenantId(),owner.userId());
            var rows=db.queryForList("SELECT * FROM agent_memory_job WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND job_id=? FOR UPDATE",owner.tenantId(),owner.userId(),run.toString(),id.toString());
            if(accounts.isEmpty() || rows.isEmpty())throw PersonalMemory.missing();var account=accounts.get(0);var old=rows.get(0);var p=(com.fasterxml.jackson.databind.node.ObjectNode)decode(old.get("plan_json"));
            if(!Set.of("CONSOLIDATE","DREAM").contains(old.get("job_type")))throw PersonalMemory.invalid();
            if(!"ON".equals(account.get("mode")) || ((Number)account.get("consent_epoch")).longValue()!=((Number)old.get("consent_epoch")).longValue() || ((Number)account.get("erase_epoch")).longValue()!=((Number)old.get("erase_epoch")).longValue() || !p.path("explicit").asBoolean(false) && ((Number)account.get("auto_extract")).intValue()!=1)throw MemoryLedger.invalidated();
            var model=models.getIfAvailable();if(model==null)throw new AgentException("MEMORY_UNAVAILABLE","Memory model unavailable");
            String generation=MemoryConsolidation.hash("retry:"+id+":"+key);
            var replay=db.queryForList("SELECT job_id,state FROM agent_memory_job WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND generation_key=?",owner.tenantId(),owner.userId(),run.toString(),generation);
            if(!replay.isEmpty())return replay.get(0);
            if(!Set.of("DEAD","CANCELLED").contains(old.get("state")))throw new AgentException("MEMORY_JOB_NOT_RETRYABLE","Only unfinished stopped tasks can be retried");
            p.remove(List.of("manifest","processedIds","memoryRefs","traceManifest","traceMemoryRefs","nextScanBefore"));
            if("DREAM".equals(old.get("job_type")))p.put("sequence",((Number)account.get("memory_seq")).longValue());
            else if(db.queryForObject("SELECT COUNT(*) FROM agent_memory_window WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND state='PENDING' AND explicit_request=? AND (?=0 OR run_id=?)",Integer.class,owner.tenantId(),owner.userId(),old.get("session_id"),p.path("explicit").asBoolean()?1:0,p.path("explicit").asBoolean()?1:0,run.toString())==0)throw new AgentException("MEMORY_JOB_NOT_RETRYABLE","No pending source window remains");
            if("CONSOLIDATE".equals(old.get("job_type")))p.put("policy",MemoryConsolidation.VERSION);
            String next=UUID.randomUUID().toString();
            db.update("INSERT INTO agent_memory_job(job_id,tenant_id,owner_user_id,session_id,run_id,consent_epoch,erase_epoch,model_version,job_type,plan_json,generation_key,available_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",next,owner.tenantId(),owner.userId(),old.get("session_id"),run.toString(),account.get("consent_epoch"),account.get("erase_epoch"),model.version(),old.get("job_type"),p.toString(),generation);
            return Map.of("job_id",next,"state","PENDING");
        });
    }
}
