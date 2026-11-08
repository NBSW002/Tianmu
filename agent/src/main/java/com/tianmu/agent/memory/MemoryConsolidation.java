package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.harness.trace.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;

/** Native conversation consolidation. Existing outbox/lease/store remain authoritative.
 * Observation -> bounded context -> sourced proposals -> ordered versioned facts.
 * No provider call is made in a transaction; no cursor advances before publication. */
@Service
@ConditionalOnExpression("${agent.memory.enabled:false}")
public final class MemoryConsolidation {
    private final com.tianmu.agent.memory.MemoryRuntimeProperties governance;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private MemoryPolicyProperties policy=MemoryPolicyProperties.defaults();
    public static final String VERSION="conversation-facts-v3";
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;
    private final MemoryLedger ledger;private final PersonalMemoryStore store;private final io.opentelemetry.api.trace.Tracer tracer;
    public MemoryConsolidation(DataSource source,ObjectMapper json,MemoryLedger ledger,PersonalMemoryStore store,io.opentelemetry.api.trace.Tracer tracer) { this(source, json, ledger, store, tracer, com.tianmu.agent.memory.MemoryRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public MemoryConsolidation(DataSource source,ObjectMapper json,MemoryLedger ledger,PersonalMemoryStore store,io.opentelemetry.api.trace.Tracer tracer, com.tianmu.agent.memory.MemoryRuntimeProperties governance) { this.governance=governance;
        db=new JdbcTemplate(source);db.setQueryTimeout(governance.queryTimeoutSeconds());tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(governance.transactionTimeoutSeconds());
        this.json=json.copy().findAndRegisterModules();this.ledger=ledger;this.store=store;this.tracer=tracer;
    }
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<MemoryModelPort> models;
    private String modelVersion(){var m=models==null?null:models.getIfAvailable();return m==null || m.version()==null?"":m.version();}
    public record Source(String messageId,String runId,String sessionId,String role,long messageSeq,long observed,String hash,String text){}
    public record Candidate(String kind,String attribute,String qualifier,String value,String text,String topic,List<Support> support){}
    public record Support(String messageId,String quote){}
    public record Proposal(List<Candidate> candidates,List<Source> sources,List<String> processedIds){ public Proposal(List<Candidate> candidates,List<Source> sources,String id){this(candidates,sources,List.of(id));} }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid consolidation data");}}
    private JsonNode decode(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid consolidation data");}}
    static String hash(String value){return com.tianmu.agent.adapters.storage.FileObjectStore.hash(value.getBytes(StandardCharsets.UTF_8));}
    static String normalize(String value){return Normalizer.normalize(value.strip(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+","");}
    private static long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static AccessScope owner(Map<String,Object> job){return new AccessScope(job.get("tenant_id").toString(),job.get("owner_user_id").toString(),Set.of());}
    private static Object[] key(AccessScope o){return new Object[]{o.tenantId(),o.userId()};}

    /** Called in the accepted user-message transaction, after the session lock. */
    public void observe(JdbcTemplate transaction,AccessScope o,UUID session,UUID run){
        transaction.update("INSERT INTO agent_memory_account(tenant_id,owner_user_id) VALUES(?,?) ON DUPLICATE KEY UPDATE tenant_id=tenant_id",key(o));
        transaction.queryForList("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",key(o));
        if(transaction.queryForObject("SELECT COUNT(*) FROM agent_memory_observation WHERE tenant_id=? AND owner_user_id=? AND run_id=?",Integer.class,o.tenantId(),o.userId(),run.toString())>0)return;
        transaction.update("UPDATE agent_memory_account SET observation_seq=observation_seq+1 WHERE tenant_id=? AND owner_user_id=?",key(o));
        transaction.update("INSERT INTO agent_memory_observation(tenant_id,owner_user_id,observation_seq,session_id,run_id,message_id,message_seq,content_hash,consent_epoch,erase_epoch,eligible) SELECT m.tenant_id,m.owner_user_id,a.observation_seq,m.session_id,m.run_id,m.message_id,m.message_seq,m.content_sha256,a.consent_epoch,a.erase_epoch,IF(a.mode='ON' AND a.auto_extract=1 AND ?=1,1,0) FROM agent_message m JOIN agent_memory_account a ON a.tenant_id=m.tenant_id AND a.owner_user_id=m.owner_user_id WHERE m.tenant_id=? AND m.owner_user_id=? AND m.run_id=? AND m.role='USER'",MemoryIntent.automaticCandidate(sourceText(transaction,o,run))?1:0,o.tenantId(),o.userId(),run.toString());
        transaction.update("INSERT INTO agent_memory_cursor(tenant_id,owner_user_id,session_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE session_id=session_id",o.tenantId(),o.userId(),session.toString());
        enqueue(transaction,o,session,run,modelVersion());
    }
    public void enqueue(JdbcTemplate transaction,AccessScope o,UUID session,UUID run,String model){enqueue(transaction,o,session,run,model,false);}
    private void enqueue(JdbcTemplate transaction,AccessScope o,UUID session,UUID run,String model,boolean explicit){
        var rows=transaction.queryForList("SELECT ob.*,r.status FROM agent_memory_observation ob JOIN agent_run r ON r.tenant_id=ob.tenant_id AND r.owner_user_id=ob.owner_user_id AND r.run_id=ob.run_id WHERE ob.tenant_id=? AND ob.owner_user_id=? AND ob.run_id=?",o.tenantId(),o.userId(),run.toString());
        if(rows.isEmpty())return;var row=rows.get(0);
        if(model.isBlank())return;
        var s=store.settings(o);boolean allowed=n(row,"eligible")==1 && s.mode()==Mode.ON && (explicit || s.autoExtract()) && s.consentEpoch()==n(row,"consent_epoch") && s.eraseEpoch()==n(row,"erase_epoch");
        transaction.update("INSERT INTO agent_memory_window(tenant_id,owner_user_id,session_id,run_id,message_seq,observation_seq,state) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE run_id=run_id",o.tenantId(),o.userId(),session.toString(),run.toString(),row.get("message_seq"),row.get("observation_seq"),allowed?"PENDING":"SKIPPED");
        transaction.update("UPDATE agent_memory_cursor SET scheduled_through=GREATEST(scheduled_through,?) WHERE tenant_id=? AND owner_user_id=? AND session_id=?",row.get("message_seq"),o.tenantId(),o.userId(),session.toString());
        if(!allowed){advance(transaction,o,session.toString());return;}
        transaction.update("UPDATE agent_memory_window SET available_at=LEAST(DATE_ADD(created_at,INTERVAL ? MICROSECOND),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? MICROSECOND)) WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND state='PENDING'",policy.maxWaitMillis()*1000L,explicit || MemoryIntent.management(sourceText(transaction,o,run))?0:policy.debounceMillis()*1000L,o.tenantId(),o.userId(),session.toString());
        var payload=new LinkedHashMap<String,Object>(Map.of("explicit",explicit,"model",model,"consent",s.consentEpoch(),"erase",s.eraseEpoch(),"observation",row.get("observation_seq"),"policy",VERSION));
        var span=io.opentelemetry.api.trace.Span.current().getSpanContext();if(span.isValid())payload.put("traceparent","00-"+span.getTraceId()+"-"+span.getSpanId()+"-"+span.getTraceFlags().asHex());
        transaction.update("INSERT INTO agent_outbox(tenant_id,outbox_id,event_id,destination,owner_user_id,session_id,run_id,payload_json) VALUES(?,?,?,'MEMORY_CONSOLIDATE',?,?,?,?)",o.tenantId(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),o.userId(),session.toString(),run.toString(),encode(payload));
    }
    private String sourceText(JdbcTemplate sql,AccessScope o,UUID run){var messages=sql.queryForList("SELECT content FROM agent_message WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND role='USER'",String.class,o.tenantId(),o.userId(),run.toString());return messages.isEmpty()?"":messages.get(0);}
    private void advance(JdbcTemplate sql,AccessScope o,String session){
        Long pending=sql.queryForObject("SELECT MIN(ob.message_seq) FROM agent_memory_observation ob LEFT JOIN agent_memory_window w ON w.tenant_id=ob.tenant_id AND w.owner_user_id=ob.owner_user_id AND w.run_id=ob.run_id WHERE ob.tenant_id=? AND ob.owner_user_id=? AND ob.session_id=? AND (w.state IS NULL OR w.state NOT IN ('DONE','SKIPPED'))",Long.class,o.tenantId(),o.userId(),session);
        Long through=sql.queryForObject("SELECT MAX(message_seq) FROM agent_memory_observation WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND message_seq<?",Long.class,o.tenantId(),o.userId(),session,pending==null?Long.MAX_VALUE:pending);
        if(through!=null)sql.update("UPDATE agent_memory_cursor SET committed_through=GREATEST(committed_through,?) WHERE tenant_id=? AND owner_user_id=? AND session_id=?",through,o.tenantId(),o.userId(),session);
    }
    /** Revoked windows are terminal decisions; retryable/failed extraction remains an explicit gap. */
    public void cancel(Map<String,Object> job,String code){
        var o=owner(job);tx.executeWithoutResult(status->{
            db.queryForList("SELECT session_id FROM agent_session WHERE tenant_id=? AND owner_user_id=? AND session_id=? FOR UPDATE",o.tenantId(),o.userId(),job.get("session_id"));
            db.queryForList("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",key(o));
            if(db.queryForList("SELECT job_id FROM agent_memory_job WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",job.get("job_id"),job.get("lease_token")).isEmpty())return;
            var settings=store.settings(o);
            var windows=db.queryForList("SELECT w.run_id,ob.message_id,ob.consent_epoch,ob.erase_epoch,w.explicit_request FROM agent_memory_window w JOIN agent_memory_observation ob ON ob.tenant_id=w.tenant_id AND ob.owner_user_id=w.owner_user_id AND ob.run_id=w.run_id WHERE w.tenant_id=? AND w.owner_user_id=? AND w.session_id=? AND w.state='PENDING'",o.tenantId(),o.userId(),job.get("session_id"));
            for(var w:windows){
                boolean invalid=settings.mode()!=Mode.ON || settings.consentEpoch()!=n(w,"consent_epoch") || settings.eraseEpoch()!=n(w,"erase_epoch") || !settings.autoExtract() && n(w,"explicit_request")==0;
                if(!invalid)try{source(o,w.get("message_id").toString());}catch(AgentException unavailable){invalid=Set.of("MEMORY_SOURCE_UNAVAILABLE","MEMORY_CONTEXT_INVALIDATED").contains(unavailable.code());}
                if(invalid)db.update("UPDATE agent_memory_window SET state='SKIPPED',job_id=?,decision_counts=? WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND state='PENDING'",job.get("job_id"),encode(Map.of("SOURCE_REVOKED",1)),o.tenantId(),o.userId(),w.get("run_id"));
            }
            boolean retry=settings.mode()==Mode.ON && (explicit(job) || settings.autoExtract()) && settings.consentEpoch()==n(job,"consent_epoch") && settings.eraseEpoch()==n(job,"erase_epoch");
            if(retry && !originAvailable(job))retry=false;
            if(retry)replan(job);
            else db.update("UPDATE agent_memory_job SET state='CANCELLED',error_code=?,lease_token=NULL,lease_until=NULL WHERE job_id=? AND state='RUNNING' AND lease_token=?",code,job.get("job_id"),job.get("lease_token"));
            advance(db,o,job.get("session_id").toString());
        });
    }
    private List<Source> load(Map<String,Object> job){
        var o=owner(job);var persisted=decode(job.get("plan_json"));
        var manifest=persisted.path("manifest");
        if(manifest.isArray()){
            var result=new ArrayList<Source>();for(var item:manifest){var s=source(o,item.path("messageId").asText());if(!s.hash().equals(item.path("hash").asText()) || !s.runId().equals(item.path("runId").asText()))throw MemoryLedger.invalidated();result.add(s);}
            return List.copyOf(result);
        }
        var windows=db.queryForList("SELECT w.*,ob.message_id FROM agent_memory_window w JOIN agent_memory_observation ob ON ob.tenant_id=w.tenant_id AND ob.owner_user_id=w.owner_user_id AND ob.run_id=w.run_id WHERE w.tenant_id=? AND w.owner_user_id=? AND w.session_id=? AND w.state='PENDING' AND w.explicit_request=? AND (?=0 OR w.run_id=?) AND ob.eligible=1 AND ob.consent_epoch=? AND ob.erase_epoch=? ORDER BY w.message_seq LIMIT ?",o.tenantId(),o.userId(),job.get("session_id"),explicit(job)?1:0,explicit(job)?1:0,job.get("run_id"),job.get("consent_epoch"),job.get("erase_epoch"),Math.min(policy.batchMessages(),persisted.path("batchLimit").asInt(policy.batchMessages())));
        if(windows.isEmpty())return List.of();
        var result=new ArrayList<Source>();var processed=new ArrayList<String>();int bytes=0;
        for(var w:windows){var item=source(o,w.get("message_id").toString());int size=item.text().getBytes(StandardCharsets.UTF_8).length;
            if(bytes+size>governance.sourceMaxBytes()){if(result.isEmpty())throw new AgentException("CANDIDATE_CAPACITY","Single message exceeds memory source budget");break;}
            result.add(item);processed.add(item.messageId());bytes+=size;
        }
        long first=result.get(0).messageSeq();
        var ids=db.queryForList("SELECT m.message_id FROM agent_message m JOIN agent_memory_observation ob ON ob.tenant_id=m.tenant_id AND ob.owner_user_id=m.owner_user_id AND ob.run_id=m.run_id JOIN agent_run r ON r.tenant_id=m.tenant_id AND r.owner_user_id=m.owner_user_id AND r.run_id=m.run_id WHERE m.tenant_id=? AND m.owner_user_id=? AND m.session_id=? AND m.message_seq<? AND m.message_seq>=? AND ob.consent_epoch=? AND ob.erase_epoch=? AND m.expires_at>UTC_TIMESTAMP(6) ORDER BY m.message_seq DESC",String.class,o.tenantId(),o.userId(),job.get("session_id"),first,Math.max(1,first-governance.sourceSequenceWindow()),job.get("consent_epoch"),job.get("erase_epoch"));
        for(String id:ids){Source item;try{item=source(o,id);}catch(AgentException e){if(Set.of("MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE").contains(e.code()))continue;throw e;}int size=item.text().getBytes(StandardCharsets.UTF_8).length;if(bytes+size>governance.sourceMaxBytes())break;result.add(item);bytes+=size;}
        result.sort(Comparator.comparingLong(Source::messageSeq));
        var plan=new LinkedHashMap<String,Object>();persisted.fields().forEachRemaining(e->plan.put(e.getKey(),json.convertValue(e.getValue(),Object.class)));
        plan.put("processedIds",processed);
        plan.put("manifest",result.stream().map(s->Map.of("messageId",s.messageId(),"hash",s.hash(),"runId",s.runId())).toList());
        if(db.update("UPDATE agent_memory_job SET plan_json=? WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",encode(plan),job.get("job_id"),job.get("lease_token"))!=1)throw MemoryLedger.invalidated();
        job.put("plan_json",encode(plan));return List.copyOf(result);
    }
    private Source source(AccessScope o,String message){
        var rows=db.queryForList("SELECT m.*,r.access_scope_json,ob.observation_seq FROM agent_message m JOIN agent_run r ON r.tenant_id=m.tenant_id AND r.owner_user_id=m.owner_user_id AND r.run_id=m.run_id JOIN agent_session ss ON ss.tenant_id=r.tenant_id AND ss.owner_user_id=r.owner_user_id AND ss.session_id=r.session_id JOIN agent_memory_observation ob ON ob.tenant_id=m.tenant_id AND ob.owner_user_id=m.owner_user_id AND ob.run_id=m.run_id WHERE m.tenant_id=? AND m.owner_user_id=? AND m.message_id=? AND m.expires_at>UTC_TIMESTAMP(6) AND r.data_expires_at>UTC_TIMESTAMP(6) AND ss.status='OPEN' AND ss.expires_at>UTC_TIMESTAMP(6) AND (m.role='USER' OR r.status IN ('SUCCEEDED','NEEDS_INPUT'))",o.tenantId(),o.userId(),message);
        if(rows.isEmpty())throw new AgentException("MEMORY_SOURCE_UNAVAILABLE","Conversation source was removed");var r=rows.get(0);
        try{var scope=json.readValue(r.get("access_scope_json").toString(),AccessScope.class);if("ASSISTANT".equals(r.get("role")) && !MemoryLedger.historyAllowed(db,json,scope,UUID.fromString(r.get("run_id").toString())))throw MemoryLedger.invalidated();}
        catch(java.io.IOException e){throw MemoryLedger.invalidated();}
        return new Source(message,r.get("run_id").toString(),r.get("session_id").toString(),r.get("role").toString(),n(r,"message_seq"),n(r,"observation_seq"),r.get("content_sha256").toString(),r.get("content").toString());
    }
    public void guard(Map<String,Object> job,List<Source> sources){
        var o=owner(job);var settings=store.settings(o);
        if(!originAvailable(job))throw new AgentException("MEMORY_SOURCE_UNAVAILABLE","Learning session is no longer available");
        if(settings.mode()!=Mode.ON || !explicit(job) && !settings.autoExtract() || settings.consentEpoch()!=n(job,"consent_epoch") || settings.eraseEpoch()!=n(job,"erase_epoch"))throw MemoryLedger.invalidated();
        if(db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",Integer.class,job.get("job_id"),job.get("lease_token"))!=1)throw new AgentException("STORAGE_FENCED","Memory job lost lease");
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())for(String run:sources.stream().map(Source::runId).distinct().toList())ledger.verifyOriginResources(o,UUID.fromString(run));
        for(var expected:sources){var live=source(o,expected.messageId());if(!live.hash().equals(expected.hash()) || !live.runId().equals(expected.runId()))throw MemoryLedger.invalidated();}
        var ids=decode(job.get("plan_json")).path("processedIds");
        for(var id:ids){var rows=db.queryForList("SELECT w.state,w.explicit_request FROM agent_memory_window w JOIN agent_memory_observation ob ON ob.tenant_id=w.tenant_id AND ob.owner_user_id=w.owner_user_id AND ob.run_id=w.run_id WHERE w.tenant_id=? AND w.owner_user_id=? AND ob.message_id=?",o.tenantId(),o.userId(),id.asText());
            if(rows.isEmpty() || !rows.get(0).get("state").equals("PENDING") || ((Number)rows.get(0).get("explicit_request")).intValue()!=(explicit(job)?1:0))throw new AgentException("MEMORY_WINDOW_CHANGED","Learning window changed; replan");
        }
    }
    private boolean originAvailable(Map<String,Object> job){
        return db.queryForObject("SELECT COUNT(*) FROM agent_run r JOIN agent_session ss ON ss.tenant_id=r.tenant_id AND ss.owner_user_id=r.owner_user_id AND ss.session_id=r.session_id WHERE r.tenant_id=? AND r.owner_user_id=? AND r.run_id=? AND r.data_expires_at>UTC_TIMESTAMP(6) AND ss.status='OPEN' AND ss.expires_at>UTC_TIMESTAMP(6)",Integer.class,job.get("tenant_id"),job.get("owner_user_id"),job.get("run_id"))==1;
    }
    private boolean explicit(Map<String,Object> job){return decode(job.get("plan_json")).path("explicit").asBoolean(false);}
    /** Called by the application after loading trusted recent conversation. No model runs here. */
    public Map<String,Object> requestSave(com.tianmu.agent.runtime.RunState run){
        if(!MemoryIntent.save(run.message,run.memory().recent()))throw new AgentException("MEMORY_ACTION_DENIED","No explicit save request in trusted conversation");
        return tx.execute(status->{
            if(db.queryForList("SELECT session_id FROM agent_session WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND current_run_id=? AND status='OPEN' FOR UPDATE",run.owner.tenantId(),run.owner.userId(),run.sessionId.toString(),run.id.toString()).isEmpty())throw MemoryLedger.invalidated();
            run.checkpoint();ledger.guard(run);var settings=store.settings(run.owner);
            if(settings.mode()!=Mode.ON)throw new AgentException("MEMORY_DISABLED","Memory saving is disabled");
            if(modelVersion().isBlank())throw new AgentException("MEMORY_UNAVAILABLE","Memory model unavailable");
            if(db.update("UPDATE agent_memory_observation SET eligible=1 WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND consent_epoch=? AND erase_epoch=?",run.owner.tenantId(),run.owner.userId(),run.id.toString(),settings.consentEpoch(),settings.eraseEpoch())!=1)throw MemoryLedger.invalidated();
            db.update("UPDATE agent_memory_window SET state='PENDING',explicit_request=1 WHERE tenant_id=? AND owner_user_id=? AND run_id=?",run.owner.tenantId(),run.owner.userId(),run.id.toString());
            enqueue(db,run.owner,run.sessionId,run.id,modelVersion(),true);
            return Map.of("status","QUEUED","runId",run.id.toString(),"saved",false);
        });
    }
    public void replan(Map<String,Object> job){
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)decode(job.get("plan_json"));
        var history=p.withArray("traceManifest");for(var ref:p.path("manifest"))history.add(ref.deepCopy());
        p.remove(List.of("manifest","processedIds"));
        db.update("UPDATE agent_memory_job SET plan_json=?,state='PENDING',lease_token=NULL,lease_until=NULL,error_code='MEMORY_WINDOW_CHANGED',available_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND) WHERE job_id=? AND state='RUNNING' AND lease_token=?",encode(p),governance.retryDelaySeconds(),job.get("job_id"),job.get("lease_token"));
    }
    public boolean reduceBatch(Map<String,Object> job){
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)decode(job.get("plan_json"));
        int count=p.path("processedIds").size();if(count<=1)return false;
        p.put("batchLimit",Math.max(1,count/2));job.put("plan_json",encode(p));replan(job);return true;
    }
    /** Recover accepted materials that had no model/outbox at acceptance, without per-message LLM polling. */
    public void recover(String model){
        var pending=db.queryForList("SELECT ob.*,COALESCE(w.explicit_request,0) AS explicit_request FROM agent_memory_observation ob LEFT JOIN agent_memory_window w ON w.tenant_id=ob.tenant_id AND w.owner_user_id=ob.owner_user_id AND w.run_id=ob.run_id WHERE ob.eligible=1 AND (w.run_id IS NULL OR w.state='PENDING') AND NOT EXISTS(SELECT 1 FROM agent_memory_job j WHERE j.tenant_id=ob.tenant_id AND j.owner_user_id=ob.owner_user_id AND j.run_id=ob.run_id AND j.job_type='CONSOLIDATE' AND JSON_UNQUOTE(JSON_EXTRACT(j.plan_json,'$.policy'))=?) AND NOT EXISTS(SELECT 1 FROM agent_outbox b WHERE b.tenant_id=ob.tenant_id AND b.owner_user_id=ob.owner_user_id AND b.run_id=ob.run_id AND b.destination='MEMORY_CONSOLIDATE' AND JSON_UNQUOTE(JSON_EXTRACT(b.payload_json,'$.policy'))=? AND b.status IN ('PENDING','INFLIGHT','SENT')) ORDER BY ob.observation_seq LIMIT ?",VERSION,VERSION,governance.topicListLimit());
        for(var ob:pending)tx.executeWithoutResult(status->{
            var o=owner(ob);db.queryForList("SELECT session_id FROM agent_session WHERE tenant_id=? AND owner_user_id=? AND session_id=? FOR UPDATE",o.tenantId(),o.userId(),ob.get("session_id"));
            db.queryForList("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",key(o));
            enqueue(db,o,UUID.fromString(ob.get("session_id").toString()),UUID.fromString(ob.get("run_id").toString()),model,n(ob,"explicit_request")==1);
        });
    }
    public void work(Map<String,Object> job,MemoryModelPort model){
        if(!VERSION.equals(decode(job.get("plan_json")).path("policy").asText()))throw new AgentException("MODEL_REVISION_UNAVAILABLE","Consolidation policy revision changed");
        var sources=phase("agent.memory.sources",()->load(job));guard(job,sources);
        if(sources.isEmpty()){db.update("UPDATE agent_memory_job SET state='SUCCEEDED',error_code=NULL,lease_token=NULL,lease_until=NULL WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",job.get("job_id"),job.get("lease_token"));return;}
        var planned=decode(job.get("plan_json")).path("processedIds");
        List<String> current=planned.isArray()?json.convertValue(planned,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}):sources.stream().filter(s->s.runId().equals(job.get("run_id")) && s.role().equals("USER")).map(Source::messageId).toList();
        db.update("UPDATE agent_memory_job SET model_calls=model_calls+1 WHERE job_id=? AND lease_token=?",job.get("job_id"),job.get("lease_token"));
        var payload=Map.of("new_message_id",current.get(current.size()-1),"new_message_ids",current,"messages",sources,"policy",VERSION);
        JsonNode result=model.evaluate(PROMPT,payload,governance.consolidationOutputTokens(),new CallControl(Instant.now().plusSeconds(governance.consolidationTimeoutSeconds()),new CancellationToken(),new ModelBudget(governance.consolidationMaxTokens(),governance.consolidationReservation()),()->{}).withDispatchBarrier(()->guard(job,sources)));
        guard(job,sources);var proposal=phase("agent.memory.candidates",()->parse(result,sources,current));
        var root=io.opentelemetry.api.trace.Span.current();
        phase("agent.memory.commit",()->{publish(job,proposal,root);return true;});
    }
    static final String PROMPT="""
        从近期多轮对话中整理当前用户明确表达、未来仍有价值的个人事实(PROFILE)、偏好(PREFERENCE)或持续目标(GOAL)。只处理 new_message_ids 指定的每条用户消息带来的新信息，旧消息仅帮助理解指代。processed_message_ids 必须按原顺序完整列出 new_message_ids；不能只处理最后一句。
        不需要用户说“记住”。“这次”是临时要求不保存；“不要记住”、反问、假设、引用、第三方、助手建议、短时情绪、敏感画像和凭据不保存。
        “不喜欢某类视频”可以是长期否定偏好；“不再喜欢”是偏好变化，不能一概忽略否定句。
        仅输出 JSON：{"complete":true,"overflow":false,"processed_message_ids":["当前消息ID"],"candidates":[
          {"kind":"PREFERENCE","attribute":"explanation_style","qualifier":"","value":"examples_first","text":"用户偏好先举例再解释。","topic":"","support":[{"message_id":"用户消息ID","quote":"逐字原话"}]}]}。
        无可记信息则 candidates=[]。支持组必须包含至少一条本批用户消息；指代对象由早前用户消息说明时也必须引用它。助手不是用户事实的独立依据。
        attribute 为稳定英文属性键，优先使用 response_length、explanation_style、response_language、response_format、school、education_status、city、video_genre、learning_goal。同类事实必须复用相同稳定属性与限定，避免另建矛盾事实；同一属性不同值仍用相同键。
        qualifier 是真实限定条件，无则空；value 为稳定短值，response_length 用 concise/detailed，explanation_style 用 examples_first/concept_first。
        回答语言固定使用 response_language；先给结论、列表等输出组织方式固定使用 response_format；回答长短固定使用 response_length。不要为这些核心偏好另造同义属性键。
        topic 仅在偏好明确限于主题时填写原话出现的连续短语，否则空；不要把全局偏好分配到临时主题。text 为不扩充事实的简洁中文陈述。
        范围不能扩大：“讲这个主题时”是主题范围，必须用先前用户原话还原主题，同时引用主题原话和本轮偏好原话；不能当成全局偏好。无法可靠还原则不保存。
        例如前一用户说“今天我们聊数据库”，本轮用户说“讲这个主题时，我长期更喜欢先举例再讲原理”，应输出 topic="数据库"、qualifier=""，support 同时逐字引用这两条用户消息；不能输出 topic=""。
        topic 已表达的限定不在 qualifier 重复填写。全局偏好（不管聊什么/所有主题）则 topic=""、qualifier=""。text 必须保留主题限定。
        用户单字“存”、确认“是的”可以承接早前用户事实，必须同时引用原事实与指令；助手建议不能作为事实依据。学校等是PROFILE，毕业更新education_status而非凭空推断学校。每项只记一件事。最多8项；超额必须 complete=false、overflow=true，不得假装完整。每个quote必须逐字来自对应用户原话。
        不补齐原话没有提供的身份。比如“我是天津大学的”只记用户提到自己来自天津大学，不推断在读学生、教师、学历、专业或毕业状态；只有用户明确说“我在读/我是学生”才能记录学生身份。
        """;
    public static Proposal parse(JsonNode root,List<Source> sources,String current){return parse(root,sources,List.of(current));}
    public static Proposal parse(JsonNode root,List<Source> sources,List<String> current){
        if(!root.isObject() || root.size()!=4 || !root.path("complete").isBoolean() || !root.path("overflow").isBoolean() || !root.path("processed_message_ids").isArray() || root.path("processed_message_ids").size()!=current.size() || !java.util.stream.StreamSupport.stream(root.path("processed_message_ids").spliterator(),false).map(JsonNode::asText).toList().equals(current) || !root.path("candidates").isArray())throw new AgentException("INVALID_MODEL_RESPONSE","Invalid consolidation envelope");
        if(!root.path("complete").booleanValue() || root.path("overflow").booleanValue() || root.path("candidates").size()>8)throw new AgentException("CANDIDATE_CAPACITY","Source needs a smaller or manually reviewed extraction");
        var byId=new HashMap<String,Source>();sources.forEach(s->byId.put(s.messageId(),s));var out=new ArrayList<Candidate>();var factKeys=new HashSet<String>();
        for(var item:root.path("candidates")){
            if(item.size()!=7)throw new AgentException("INVALID_MODEL_RESPONSE","Invalid memory candidate fields");
            for(String f:List.of("kind","attribute","qualifier","value","text","topic"))if(!item.path(f).isTextual())throw new AgentException("INVALID_MODEL_RESPONSE","Invalid memory candidate type");
            String kind=item.path("kind").asText(),attribute=item.path("attribute").asText(),qualifier=item.path("qualifier").asText(),value=item.path("value").asText(),topic=item.path("topic").asText();
            if(!Set.of("PREFERENCE","GOAL","PROFILE").contains(kind) || !attribute.matches("[a-z][a-z0-9_]{0,63}") || qualifier.length()>80 || value.isBlank() || value.length()>256 || topic.length()>40 || !item.path("support").isArray() || item.path("support").isEmpty() || item.path("support").size()>6)throw new AgentException("INVALID_MODEL_RESPONSE","Invalid memory candidate");
            var refs=new ArrayList<Support>();var ids=new HashSet<String>();
            for(var support:item.path("support")){
                var id=support.path("message_id").asText();var quote=support.path("quote").asText();var source=byId.get(id);
                if(support.size()!=2 || source==null || !source.role().equals("USER") || quote.isBlank() || quote.length()>1024 || !source.text().contains(quote) || !ids.add(id))throw new AgentException("INVALID_MODEL_RESPONSE","Memory fact has unsupported source");
                refs.add(new Support(id,quote));
            }
            if(Collections.disjoint(ids,current) || !topic.isEmpty() && sources.stream().filter(s->s.role().equals("USER")).noneMatch(s->s.text().contains(topic)))throw new AgentException("INVALID_MODEL_RESPONSE","Fact is not supported by current user");
            if(refs.size()==1 && byId.get(refs.get(0).messageId()).text().strip().matches("^(存|保存|存一下|是的|对|确认|好|好的)[。！!]*$"))throw new AgentException("INVALID_MODEL_RESPONSE","A confirmation requires the original user fact");
            if(!topic.isEmpty() && refs.stream().noneMatch(ref->ref.quote().contains(topic)))throw new AgentException("INVALID_MODEL_RESPONSE","Topic requires its quoted user source");
            if(!factKeys.add(kind+":"+attribute+":"+normalize(qualifier)+":"+normalize(topic)))throw new AgentException("INVALID_MODEL_RESPONSE","Conflicting candidates for the same fact");
            new Content(Scope.user(),Kind.valueOf(kind),item.path("text").asText(),null);
            out.add(new Candidate(kind,attribute,qualifier,value,item.path("text").asText(),topic,List.copyOf(refs)));
        }
        return new Proposal(List.copyOf(out),List.copyOf(sources),current);
    }
    private <T>T phase(String name,java.util.function.Supplier<T> operation){
        var span=tracer.spanBuilder(name).startSpan();TraceNode.begin(span,name);
        try(var scope=span.makeCurrent()){var result=operation.get();TraceNode.success(span);span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);return result;}
        catch(RuntimeException e){TraceNode.failure(span,e);span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);throw e;}finally{span.end();}
    }
    public void publish(Map<String,Object> job,Proposal proposal,io.opentelemetry.api.trace.Span root){
        var o=owner(job);var session=job.get("session_id").toString();
        Map<String,Integer> counts=tx.execute(status->{
            // Stable session-first lock order. Source package currently stays within one session.
            db.queryForList("SELECT session_id FROM agent_session WHERE tenant_id=? AND owner_user_id=? AND session_id=? FOR UPDATE",o.tenantId(),o.userId(),session);
            db.queryForList("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",key(o));
            db.queryForList("SELECT job_id FROM agent_memory_job WHERE job_id=? FOR UPDATE",job.get("job_id"));guard(job,proposal.sources());
            var countsIn=new LinkedHashMap<String,Integer>();int index=0;
            for(var candidate:proposal.candidates()){
                var decision=commitCandidate(job,proposal,candidate,index++);countsIn.merge(decision,1,Integer::sum);
            }
            if(db.update("UPDATE agent_memory_job SET state='SUCCEEDED',error_code=NULL,lease_token=NULL,lease_until=NULL WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",job.get("job_id"),job.get("lease_token"))!=1)throw MemoryLedger.invalidated();
            for(String id:proposal.processedIds())db.update("UPDATE agent_memory_window w JOIN agent_memory_observation ob ON ob.tenant_id=w.tenant_id AND ob.owner_user_id=w.owner_user_id AND ob.run_id=w.run_id SET w.state='DONE',w.job_id=?,w.decision_counts=? WHERE w.tenant_id=? AND w.owner_user_id=? AND ob.message_id=? AND w.state='PENDING'",job.get("job_id"),encode(countsIn),o.tenantId(),o.userId(),id);
            advance(db,o,session);return countsIn;
        });
        TraceDetails.output(Map.of("processedMessageIds",proposal.processedIds(),"decisions",counts));
        var attrs=decisionStats(proposal.candidates().size(),proposal.sources().size(),counts);
        TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),attrs);TraceAttributes.apply(root,attrs);
    }
    static Map<String,Object> decisionStats(int candidates,int sources,Map<String,Integer> counts){
        var attrs=new LinkedHashMap<String,Object>();attrs.put("memory.candidate_count",candidates);attrs.put("memory.selected_count",counts.getOrDefault("ADD",0)+counts.getOrDefault("REANCHOR_AUTO",0)+counts.getOrDefault("SUPERSEDE_AUTO",0));
        attrs.put("memory.added_count",counts.getOrDefault("ADD",0)+counts.getOrDefault("REANCHOR_AUTO",0));attrs.put("memory.updated_count",counts.getOrDefault("SUPERSEDE_AUTO",0));attrs.put("memory.duplicate_count",counts.getOrDefault("NOOP_DUPLICATE",0));attrs.put("memory.conflict_count",counts.getOrDefault("CONFLICT",0));attrs.put("memory.source_messages",sources);attrs.put("memory.policy_version",VERSION);
        return Map.copyOf(attrs);
    }
    private String commitCandidate(Map<String,Object> job,Proposal p,Candidate c,int index){
        var o=owner(job);String topic=c.topic();Scope scope=topic.isEmpty()?Scope.user():new Scope(ScopeType.TOPIC,ledger.topic(o,topic).toString());
        var identity=new TreeMap<String,String>();identity.put("scope",scope.type().name());identity.put("scope_key",scope.key());identity.put("kind",c.kind());identity.put("attribute",c.attribute());identity.put("qualifier",normalize(c.qualifier()));
        String fact=hash(encode(identity)),value=hash(normalize(c.value()));long observed=p.sources().stream().filter(s->c.support().stream().anyMatch(r->r.messageId().equals(s.messageId()))).mapToLong(Source::observed).max().orElseThrow();
        var heads=db.queryForList("SELECT * FROM agent_memory_fact WHERE tenant_id=? AND owner_user_id=? AND fact_key=? FOR UPDATE",o.tenantId(),o.userId(),fact);
        String decision="ADD";UUID id=null;long expected=0;
        if(!heads.isEmpty()){
            var h=heads.get(0);id=UUID.fromString(h.get("memory_id").toString());expected=n(h,"memory_version");
            var rows=db.queryForList("SELECT i.status,i.current_version,s.source_kind FROM agent_memory_item i JOIN agent_memory_dependency d ON d.tenant_id=i.tenant_id AND d.owner_user_id=i.owner_user_id AND d.memory_id=i.memory_id AND d.version=i.current_version JOIN agent_memory_source s ON s.tenant_id=d.tenant_id AND s.owner_user_id=d.owner_user_id AND s.source_id=d.source_id WHERE i.tenant_id=? AND i.owner_user_id=? AND i.memory_id=?",o.tenantId(),o.userId(),id.toString());
            boolean erased=db.queryForObject("SELECT COUNT(*) FROM agent_memory_tombstone WHERE tenant_id=? AND owner_user_id=? AND target_id=?",Integer.class,o.tenantId(),o.userId(),id.toString())>0;
            if(rows.isEmpty() || erased)decision="REJECT_POLICY";
            else if(n(rows.get(0),"current_version")!=expected || rows.stream().anyMatch(r->!"AUTO_EXTRACTED".equals(r.get("source_kind")) && !(explicit(job) && "EXPLICIT_DECLARATION".equals(r.get("source_kind")))))decision="CONFLICT";
            else if(n(h,"observation_seq")>observed)decision="STALE";
            else if(!"ACTIVE".equals(rows.get(0).get("status")) || db.queryForObject("SELECT COUNT(*) FROM agent_memory_revision r WHERE r.tenant_id=? AND r.owner_user_id=? AND r.memory_id=? AND r.version=? AND r.content<>'' AND (r.expires_at IS NULL OR r.expires_at>UTC_TIMESTAMP(6)) AND "+MemorySources.REVISION_READABLE,Integer.class,o.tenantId(),o.userId(),id.toString(),expected)==0){decision="REANCHOR_AUTO";id=null;expected=0;}
            else if(h.get("value_hash").equals(value) && !explicit(job))decision="NOOP_DUPLICATE";
            else decision="SUPERSEDE_AUTO";
        }
        if("NOOP_DUPLICATE".equals(decision))db.update("UPDATE agent_memory_fact SET observation_seq=GREATEST(observation_seq,?) WHERE tenant_id=? AND owner_user_id=? AND fact_key=?",observed,o.tenantId(),o.userId(),fact);
        if(Set.of("ADD","SUPERSEDE_AUTO","REANCHOR_AUTO").contains(decision)){
            // The store is nested in this transaction; its mutation receipt and our window commit roll back together.
            var settings=store.settings(o);var content=new Content(scope,Kind.valueOf(c.kind()),c.text(),null);
            var receipt=expected==0?ledger.declareBackground(o,UUID.fromString(job.get("session_id").toString()),UUID.fromString(job.get("run_id").toString()),content,"consolidate:"+job.get("job_id")+":"+index,explicit(job),settings.consentEpoch(),settings.eraseEpoch(),()->guard(job,p.sources()))
                :store.update(o,id,expected,content,"consolidate:"+job.get("job_id")+":"+index);
            id=receipt.id();bindSupport(o,receipt,c,p.sources(),explicit(job));
            db.update("INSERT INTO agent_memory_index(tenant_id,owner_user_id,memory_id,version,is_core,importance) VALUES(?,?,?,?,?,?)",o.tenantId(),o.userId(),id.toString(),receipt.version(),MemorySelection.core(c.kind(),c.attribute(),c.topic(),c.qualifier())?1:0,50);
            db.update("INSERT INTO agent_memory_fact(tenant_id,owner_user_id,fact_key,identity_json,memory_id,memory_version,observation_seq,value_hash) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE identity_json=VALUES(identity_json),memory_id=VALUES(memory_id),memory_version=VALUES(memory_version),observation_seq=VALUES(observation_seq),value_hash=VALUES(value_hash)",o.tenantId(),o.userId(),fact,encode(identity),id.toString(),receipt.version(),observed,value);
        }
        db.update("INSERT INTO agent_memory_candidate(tenant_id,owner_user_id,job_id,candidate_index,fact_key,decision,memory_id,details_json) VALUES(?,?,?,?,?,?,?,?)",o.tenantId(),o.userId(),job.get("job_id"),index,fact,decision,id==null?null:id.toString(),encode(Map.of("support",c.support().stream().map(Support::messageId).toList(),"topic",topic)));
        return decision;
    }
    private void bindSupport(AccessScope o,Receipt receipt,Candidate c,List<Source> sources,boolean explicit){
        // Drop the temporary single source only inside this publication transaction.
        var old=db.queryForList("SELECT source_id FROM agent_memory_dependency WHERE tenant_id=? AND owner_user_id=? AND memory_id=? AND version=?",String.class,o.tenantId(),o.userId(),receipt.id().toString(),receipt.version());
        db.update("DELETE FROM agent_memory_dependency WHERE tenant_id=? AND owner_user_id=? AND memory_id=? AND version=?",o.tenantId(),o.userId(),receipt.id().toString(),receipt.version());
        for(String source:old)db.update("DELETE FROM agent_memory_source WHERE tenant_id=? AND owner_user_id=? AND source_id=?",o.tenantId(),o.userId(),source);
        for(var ref:c.support()){
            var s=sources.stream().filter(x->x.messageId().equals(ref.messageId())).findFirst().orElseThrow();var id=UUID.randomUUID();
            var expiry=db.queryForObject("SELECT LEAST(m.expires_at,r.data_expires_at,ss.expires_at) FROM agent_message m JOIN agent_run r ON r.tenant_id=m.tenant_id AND r.run_id=m.run_id JOIN agent_session ss ON ss.tenant_id=r.tenant_id AND ss.session_id=r.session_id WHERE m.tenant_id=? AND m.owner_user_id=? AND m.message_id=?",Timestamp.class,o.tenantId(),o.userId(),s.messageId());
            db.update("INSERT INTO agent_memory_source(tenant_id,owner_user_id,source_id,source_kind,declaration,content_hash,created_at,origin_session_id,origin_run_id,source_expires_at,origin_message_id,origin_message_hash) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6),?,?,?,?,?)",o.tenantId(),o.userId(),id.toString(),explicit?"EXPLICIT_DECLARATION":"AUTO_EXTRACTED",ref.quote(),hash(ref.quote()),s.sessionId(),s.runId(),expiry,s.messageId(),s.hash());
            db.update("INSERT INTO agent_memory_dependency(tenant_id,owner_user_id,memory_id,version,source_id) VALUES(?,?,?,?,?)",o.tenantId(),o.userId(),receipt.id().toString(),receipt.version(),id.toString());
        }
        db.update("UPDATE agent_memory_revision SET support_policy='ALL_REQUIRED_V2' WHERE tenant_id=? AND owner_user_id=? AND memory_id=? AND version=?",o.tenantId(),o.userId(),receipt.id().toString(),receipt.version());
    }
}
