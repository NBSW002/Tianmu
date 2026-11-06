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
@Service @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${agent.memory.enabled:false} or ${agent.compaction.enabled:false}")
public final class MemoryBackground {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private CompactionService compaction;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private MemoryConsolidation consolidation;
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
    public MemoryBackground(DataSource source,MemoryLedger ledger,ObjectProvider<MemoryModelPort> models,@Qualifier("memoryExecutor")ThreadPoolTaskExecutor executor,ObjectMapper json,Tracer tracer,TraceDetails details){db=new JdbcTemplate(source);db.setQueryTimeout(3);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(5);this.ledger=ledger;this.models=models;this.executor=executor;this.json=json;this.tracer=tracer;this.details=details;}
    /** Caller already owns the run/session transaction. */
    public void enqueue(JdbcTemplate transaction,AccessScope owner,UUID session,UUID run){
        if(compaction!=null)compaction.enqueue(transaction,owner,session,run);
        if(!memoryEnabled)return;
        var model=models.getIfAvailable();if(model==null)return;
        if(consolidation!=null){consolidation.enqueue(transaction,owner,session,run,model.version());return;}
        var accounts=transaction.queryForList("SELECT a.consent_epoch,a.erase_epoch FROM agent_memory_account a WHERE a.tenant_id=? AND a.owner_user_id=? AND a.mode='ON' AND a.auto_extract=1 AND EXISTS(SELECT 1 FROM agent_run r WHERE r.tenant_id=a.tenant_id AND r.owner_user_id=a.owner_user_id AND r.run_id=? AND r.status='SUCCEEDED')",owner.tenantId(),owner.userId(),run.toString());
        if(accounts.isEmpty())return;var a=accounts.get(0);var payload=new LinkedHashMap<String,Object>();payload.put("consent",a.get("consent_epoch"));payload.put("erase",a.get("erase_epoch"));payload.put("model",model.version());
        var span=Span.current().getSpanContext();if(span.isValid())payload.put("traceparent","00-"+span.getTraceId()+"-"+span.getSpanId()+"-"+span.getTraceFlags().asHex());
        transaction.update("INSERT INTO agent_outbox(tenant_id,outbox_id,event_id,destination,owner_user_id,session_id,run_id,payload_json) VALUES(?,?,?,'MEMORY_EXTRACT',?,?,?,?)",owner.tenantId(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),owner.userId(),session.toString(),run.toString(),ledger.encode(payload));
    }
    public void accept(Map<String,Object> event){
        var payload=decode(event.get("payload_json"));
        if("MEMORY_COMPACT_CHECK".equals(event.get("destination"))){
            if(compaction==null)throw new AgentException("MEMORY_DISABLED","Compaction worker is disabled");
            var check=new LinkedHashMap<>(event);check.put("consent_epoch",payload.path("consent").asLong());check.put("erase_epoch",payload.path("erase").asLong());compaction.catchUp(check);return;
        }
        boolean compact="MEMORY_COMPACT".equals(event.get("destination"));boolean consolidate="MEMORY_CONSOLIDATE".equals(event.get("destination"));
        String generation=compact?com.tianmu.agent.adapters.storage.FileObjectStore.hash((payload.path("fingerprint").asText()+":"+payload.path("consent").asLong()+":"+payload.path("erase").asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8)):consolidate?MemoryConsolidation.hash(payload.path("policy").asText()+":"+payload.path("consent").asLong()+":"+payload.path("erase").asLong()):"";
        db.update("INSERT INTO agent_memory_job(job_id,tenant_id,owner_user_id,session_id,run_id,consent_epoch,erase_epoch,model_version,trace_parent,job_type,plan_json,generation_key,available_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE job_id=job_id",UUID.randomUUID().toString(),event.get("tenant_id"),event.get("owner_user_id"),event.get("session_id"),event.get("run_id"),payload.path("consent").asLong(),payload.path("erase").asLong(),payload.path("model").asText(),payload.path("traceparent").asText(null),compact?"COMPACT":consolidate?"CONSOLIDATE":"EXTRACT",payload.toString(),generation);
    }
    public void observe(JdbcTemplate transaction,AccessScope owner,UUID session,UUID run){if(consolidation!=null)consolidation.observe(transaction,owner,session,run);}
    private JsonNode decode(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid memory job payload");}}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=2000)
    public void tick(){if(!busy.compareAndSet(false,true))return;try{executor.execute(()->{try{var job=claim();if(job!=null){current.set(job);work(job);}}finally{current.set(null);busy.set(false);}});}catch(java.util.concurrent.RejectedExecutionException e){busy.set(false);}}
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=10000)
    public void heartbeat(){var job=current.get();if(job!=null)db.update("UPDATE agent_memory_job SET lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE job_id=? AND state='RUNNING' AND lease_token=?",job.get("job_id"),job.get("lease_token"));}
    private Map<String,Object> claim(){return tx.execute(status->{
        db.update("UPDATE agent_memory_job SET state=IF(attempts>=5,'DEAD','PENDING'),lease_token=NULL,lease_until=NULL WHERE state='RUNNING' AND lease_until<UTC_TIMESTAMP(6)");
        var rows=db.queryForList("SELECT * FROM agent_memory_job WHERE state='PENDING' AND available_at<=UTC_TIMESTAMP(6) ORDER BY available_at,job_id LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;
        var job=rows.get(0);String token=UUID.randomUUID().toString();db.update("UPDATE agent_memory_job SET state='RUNNING',attempts=attempts+1,lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE job_id=?",token,job.get("job_id"));job.put("lease_token",token);return job;
    });}
    private void finish(Map<String,Object> job,String state,String code){db.update("UPDATE agent_memory_job SET state=?,error_code=?,lease_token=NULL,lease_until=NULL,available_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 SECOND) WHERE job_id=? AND state='RUNNING' AND lease_token=?",state,code,job.get("job_id"),job.get("lease_token"));}
    private void finishCompaction(Map<String,Object> job){tx.executeWithoutResult(status->{
        if(db.update("UPDATE agent_memory_job SET state='SUCCEEDED',error_code=NULL,lease_token=NULL,lease_until=NULL WHERE job_id=? AND state='RUNNING' AND lease_token=?",job.get("job_id"),job.get("lease_token"))!=1)throw new AgentException("STORAGE_FENCED","Compaction completion lost its lease");
        db.update("INSERT INTO agent_outbox(tenant_id,outbox_id,event_id,destination,owner_user_id,session_id,run_id,payload_json) VALUES(?,?,?,'MEMORY_COMPACT_CHECK',?,?,?,?)",job.get("tenant_id"),UUID.randomUUID().toString(),UUID.randomUUID().toString(),job.get("owner_user_id"),job.get("session_id"),job.get("run_id"),ledger.encode(Map.of("consent",job.get("consent_epoch"),"erase",job.get("erase_epoch"))));
    });}
    private void extractionBarrier(Map<String,Object> job){
        Integer permitted=db.queryForObject("SELECT COUNT(*) FROM agent_memory_job j JOIN agent_run r ON r.tenant_id=j.tenant_id AND r.owner_user_id=j.owner_user_id AND r.run_id=j.run_id AND r.session_id=j.session_id JOIN agent_session s ON s.tenant_id=r.tenant_id AND s.owner_user_id=r.owner_user_id AND s.session_id=r.session_id JOIN agent_memory_account a ON a.tenant_id=j.tenant_id AND a.owner_user_id=j.owner_user_id WHERE j.job_id=? AND j.tenant_id=? AND j.owner_user_id=? AND j.state='RUNNING' AND j.lease_token=? AND j.lease_until>UTC_TIMESTAMP(6) AND r.status='SUCCEEDED' AND r.data_expires_at>UTC_TIMESTAMP(6) AND s.status='OPEN' AND s.expires_at>UTC_TIMESTAMP(6) AND a.mode='ON' AND a.auto_extract=1 AND a.consent_epoch=j.consent_epoch AND a.erase_epoch=j.erase_epoch",Integer.class,job.get("job_id"),job.get("tenant_id"),job.get("owner_user_id"),job.get("lease_token"));
        if(permitted==null || permitted!=1)throw MemoryLedger.invalidated();
    }
    private void work(Map<String,Object> job){
        boolean compact="COMPACT".equals(job.get("job_type"));
        var builder=tracer.spanBuilder(compact?"agent.context.compact":"agent.memory.extract").setNoParent();Object parent=job.get("trace_parent");
        if(parent instanceof String p && p.matches("00-[a-f0-9]{32}-[a-f0-9]{16}-[a-f0-9]{2}"))builder.addLink(SpanContext.createFromRemoteParent(p.substring(3,35),p.substring(36,52),TraceFlags.fromHex(p,53),TraceState.getDefault()));
        var span=builder.startSpan();TraceNode.begin(span,compact?"agent.context.compact":"agent.memory.extract");String tenant=job.get("tenant_id").toString(),owner=job.get("owner_user_id").toString();UUID run=UUID.fromString(job.get("run_id").toString()),session=UUID.fromString(job.get("session_id").toString());
        db.update("UPDATE agent_memory_job SET trace_id=? WHERE job_id=? AND lease_token=?",span.getSpanContext().getTraceId(),job.get("job_id"),job.get("lease_token"));
        try(var active=span.makeCurrent();var bound=details.bind(Context.current(),run).makeCurrent()){
            TraceDetails.protectMemory();TraceAttributes.apply(span,Map.of("run.id",run.toString(),"session.id",session.toString(),"tenant.id",tenant,"actor.id",owner,"span.type","memory_extract","memory.job_id",job.get("job_id").toString()));
            var model=models.getIfAvailable();
            if(compact){
                if(compaction==null || model==null)throw new AgentException("MODEL_REVISION_UNAVAILABLE","Compaction model is unavailable");
                TraceAttributes.apply(span,Map.of("span.type","context_compact"));
                compaction.generate(job,model,()->{if(db.queryForList("SELECT job_id FROM agent_memory_job WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",job.get("job_id"),job.get("lease_token")).isEmpty())throw new AgentException("STORAGE_FENCED","Compaction lease expired");});
                finishCompaction(job);TraceNode.success(span);span.setStatus(StatusCode.OK);return;
            }
            if(!memoryEnabled){finish(job,"CANCELLED","MEMORY_DISABLED");TraceAttributes.apply(span,Map.of("node.status","CANCELLED"));return;}
            if(model==null || !model.version().equals(job.get("model_version")))throw new AgentException("MODEL_REVISION_UNAVAILABLE","Memory model revision changed");
            if("CONSOLIDATE".equals(job.get("job_type"))){
                if(consolidation==null)throw new AgentException("MEMORY_DISABLED","Consolidation is disabled");
                consolidation.work(job,model);TraceNode.success(span);span.setStatus(StatusCode.OK);return;
            }
            var rows=db.queryForList("SELECT r.request_json,r.access_scope_json FROM agent_run r JOIN agent_session s ON s.tenant_id=r.tenant_id AND s.session_id=r.session_id JOIN agent_memory_account a ON a.tenant_id=r.tenant_id AND a.owner_user_id=r.owner_user_id WHERE r.tenant_id=? AND r.owner_user_id=? AND r.run_id=? AND r.status='SUCCEEDED' AND r.data_expires_at>UTC_TIMESTAMP(6) AND s.status='OPEN' AND s.expires_at>UTC_TIMESTAMP(6) AND a.mode='ON' AND a.auto_extract=1 AND a.consent_epoch=? AND a.erase_epoch=?",tenant,owner,run.toString(),job.get("consent_epoch"),job.get("erase_epoch"));
            if(rows.isEmpty()){finish(job,"CANCELLED","MEMORY_SOURCE_UNAVAILABLE");return;}
            var row=rows.get(0);String text=decode(row.get("request_json")).path("message").asText();
            if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new AgentException("INVALID_REQUEST","Source exceeds extraction budget");
            AccessScope scope=json.readValue(row.get("access_scope_json").toString(),AccessScope.class);
            db.update("UPDATE agent_memory_job SET model_calls=model_calls+1 WHERE job_id=? AND lease_token=?",job.get("job_id"),job.get("lease_token"));
            extractionBarrier(job);
            var result=model.evaluate("从当前用户本人原话中提取适合跨会话承接的明确长期偏好或持续目标。只保存原话直接支持的事实。忽略临时要求、短时情绪、否定、假设、引用、第三方内容、模型建议、敏感画像和凭据。quote 必须逐字来自原话，不改写或扩充。topic 可以是原话中出现的简短主题，无法确定则空字符串。仅返回 JSON {\"items\":[{\"quote\":\"原话片段\",\"kind\":\"PREFERENCE 或 GOAL\",\"topic\":\"主题或空字符串\"}]}，最多8条，无记忆则空数组。",Map.of("user_message",text),1024,new CallControl(Instant.now().plusSeconds(15),new CancellationToken(),new ModelBudget(8192,4096),()->{}).withDispatchBarrier(()->extractionBarrier(job)));
            extractionBarrier(job);
            if(!result.isObject() || result.size()!=1 || !result.path("items").isArray() || result.path("items").size()>8)throw new AgentException("INVALID_MODEL_RESPONSE","Invalid memory candidates");
            var candidates=new ArrayList<Content>();
            for(var item:result.path("items")){
                String quote=item.path("quote").asText();String kind=item.path("kind").asText();
                if(item.size()!=3 || quote.isBlank() || !text.contains(quote) || !Set.of("PREFERENCE","GOAL").contains(kind))throw new AgentException("INVALID_MODEL_RESPONSE","Unsupported memory candidate");
                candidates.add(new Content(Scope.user(),Kind.valueOf(kind),quote,null));
            }
            int accepted=0;
            for(int i=0;i<candidates.size();i++){
                var candidate=candidates.get(i);
                if(db.queryForObject("SELECT COUNT(*) FROM agent_memory_revision r JOIN agent_memory_item i ON i.tenant_id=r.tenant_id AND i.owner_user_id=r.owner_user_id AND i.memory_id=r.memory_id AND i.current_version=r.version WHERE r.tenant_id=? AND r.owner_user_id=? AND i.status='ACTIVE' AND r.scope_type='USER' AND r.content=?",Integer.class,tenant,owner,candidate.text())>0)continue;
                if(db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",Integer.class,job.get("job_id"),job.get("lease_token"))==0)throw new AgentException("STORAGE_FENCED","Memory lease expired");
                ledger.declare(scope,session,run,candidate,"extract:"+run+":"+i,false,((Number)job.get("consent_epoch")).longValue(),((Number)job.get("erase_epoch")).longValue(),()->{
                    if(db.queryForList("SELECT job_id FROM agent_memory_job WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",job.get("job_id"),job.get("lease_token")).isEmpty())throw new AgentException("STORAGE_FENCED","Memory lease expired");
                });accepted++;
            }
            TraceAttributes.apply(span,Map.of("memory.candidate_count",candidates.size(),"memory.selected_count",accepted));finish(job,"SUCCEEDED",null);TraceNode.success(span);span.setStatus(StatusCode.OK);
        }catch(Exception e){
            String code=e instanceof AgentException a?a.code():e instanceof org.springframework.dao.DataAccessException?"STORAGE_UNAVAILABLE":"MEMORY_EXTRACTION_FAILED";
            boolean retry=Set.of("MODEL_UNAVAILABLE","MODEL_CALL_TIMEOUT","MODEL_ERROR","MEMORY_UNAVAILABLE","STORAGE_UNAVAILABLE").contains(code) && ((Number)job.get("attempts")).intValue()<4;
            if(consolidation!=null && "CONSOLIDATE".equals(job.get("job_type")) && Set.of("MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE").contains(code))consolidation.cancel(job,code);
            else finish(job,retry?"PENDING":Set.of("MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE").contains(code)?"CANCELLED":"DEAD",code);
            span.setStatus(StatusCode.ERROR,code);
            TraceAttributes.apply(span,Map.of("node.status",Set.of("MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE").contains(code)?"CANCELLED":"FAILED","error.code",code));
        }finally{span.end();}
    }
    public List<Map<String,Object>> jobs(AccessScope owner,UUID run){return db.queryForList("SELECT j.job_id,j.job_type,j.state,j.attempts,j.model_calls,j.model_version,j.error_code,j.trace_id,j.created_at,w.decision_counts FROM agent_memory_job j LEFT JOIN agent_memory_window w ON w.tenant_id=j.tenant_id AND w.owner_user_id=j.owner_user_id AND w.job_id=j.job_id WHERE j.tenant_id=? AND j.owner_user_id=? AND j.run_id=? ORDER BY j.created_at LIMIT 20",owner.tenantId(),owner.userId(),run.toString());}
}
