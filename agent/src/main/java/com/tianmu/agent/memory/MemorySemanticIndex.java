package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.harness.trace.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable version-keyed derivation. SQL source checks are authoritative, vectors are disposable. */
@Service
@ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
@EnableConfigurationProperties(MemorySemanticPolicy.class)
public final class MemorySemanticIndex {
    private final JdbcTemplate db;private final ObjectProvider<MemoryEmbedding> models;private final ObjectMapper json;
    private final MemoryLedger ledger;private final MemorySemanticPolicy policy;private final ThreadPoolTaskExecutor executor;
    private final AtomicBoolean running=new AtomicBoolean();
    public MemorySemanticIndex(DataSource source,ObjectProvider<MemoryEmbedding> models,ObjectMapper json,MemoryLedger ledger,MemorySemanticPolicy policy,@Qualifier("memoryIndexExecutor") ThreadPoolTaskExecutor executor){
        db=new JdbcTemplate(source);db.setQueryTimeout(3);this.models=models;this.json=json;this.ledger=ledger;this.policy=policy;this.executor=executor;
    }
    @Scheduled(scheduler="maintenanceScheduler",fixedDelayString="${agent.memory.semantic.poll-millis:5000}")
    public void schedule(){
        if(models.getIfAvailable()==null || !running.compareAndSet(false,true))return;
        try{executor.execute(()->{try{index();}finally{running.set(false);}});}catch(java.util.concurrent.RejectedExecutionException e){running.set(false);}
    }
    public void index(){
        var model=models.getIfAvailable();if(model==null)return;
        Instant batchDeadline=Instant.now().plusSeconds(policy.batchTimeoutSeconds());
        // Discover committed revisions directly: a crash between fact commit and scheduling cannot lose work.
        var rows=db.queryForList("SELECT r.tenant_id,r.owner_user_id,r.memory_id,r.version FROM agent_memory_revision r JOIN agent_memory_item i ON i.tenant_id=r.tenant_id AND i.owner_user_id=r.owner_user_id AND i.memory_id=r.memory_id JOIN agent_memory_account a ON a.tenant_id=r.tenant_id AND a.owner_user_id=r.owner_user_id LEFT JOIN agent_memory_embedding e ON e.tenant_id=r.tenant_id AND e.owner_user_id=r.owner_user_id AND e.memory_id=r.memory_id AND e.version=r.version AND e.model_version=? WHERE r.scope_type='USER' AND r.valid_to_seq IS NULL AND i.status='ACTIVE' AND a.mode<>'OFF' AND r.content<>'' AND (r.expires_at IS NULL OR r.expires_at>UTC_TIMESTAMP(6)) AND "+MemorySources.REVISION_READABLE+" AND (e.memory_id IS NULL OR (e.state='FAILED' AND e.next_attempt_at<=UTC_TIMESTAMP(6))) ORDER BY COALESCE(e.next_attempt_at,r.created_at),r.memory_id LIMIT ?",model.version(),policy.batchItems());
        for(var row:rows){
            if(Thread.currentThread().isInterrupted() || !Instant.now().isBefore(batchDeadline))break;
            Object[] keys={row.get("tenant_id"),row.get("owner_user_id"),row.get("memory_id"),row.get("version"),model.version()};
            Map<String,Object> fence=null;
            try{
                var owner=new AgentTypes.AccessScope(keys[0].toString(),keys[1].toString(),Set.of());
                var settings=db.queryForMap("SELECT memory_seq,consent_epoch,erase_epoch,mode FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=?",keys[0],keys[1]);
                fence=settings;if(settings.get("mode").equals("OFF"))continue;
                var entry=ledger.read(owner,UUID.fromString(keys[2].toString()),((Number)keys[3]).longValue(),((Number)settings.get("memory_seq")).longValue());
                String content=entry.content().text();
                Runnable check=()->{
                    var current=db.queryForMap("SELECT consent_epoch,erase_epoch,mode FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=?",keys[0],keys[1]);
                    if(!current.get("consent_epoch").equals(settings.get("consent_epoch")) || !current.get("erase_epoch").equals(settings.get("erase_epoch")) || current.get("mode").equals("OFF"))throw MemoryLedger.invalidated();
                    ledger.read(owner,entry.id(),entry.version(),((Number)settings.get("memory_seq")).longValue());
                };
                check.run();var vectors=model.embed(List.of(content),new CallControl(batchDeadline,new CancellationToken()).withDispatchBarrier(check));
                if(vectors.size()!=1 || vectors.get(0).size()!=model.dimension())throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid memory vector dimension");
                check.run();vector(vectors.get(0));String encoded=json.writeValueAsString(vectors.get(0));
                // INSERT SELECT rechecks source availability after provider IO; deletion cannot resurrect a vector.
                db.update("INSERT INTO agent_memory_embedding(tenant_id,owner_user_id,memory_id,version,model_version,dimension,vector_json,state,attempts,next_attempt_at,generated_at) SELECT r.tenant_id,r.owner_user_id,r.memory_id,r.version,?,?,?,'READY',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM agent_memory_revision r JOIN agent_memory_item i ON i.tenant_id=r.tenant_id AND i.owner_user_id=r.owner_user_id AND i.memory_id=r.memory_id JOIN agent_memory_account a ON a.tenant_id=r.tenant_id AND a.owner_user_id=r.owner_user_id WHERE r.tenant_id=? AND r.owner_user_id=? AND r.memory_id=? AND r.version=? AND i.status='ACTIVE' AND a.mode<>'OFF' AND a.consent_epoch=? AND a.erase_epoch=? AND r.content=? AND r.valid_to_seq IS NULL AND "+MemorySources.REVISION_READABLE+" ON DUPLICATE KEY UPDATE vector_json=VALUES(vector_json),state='READY',dimension=VALUES(dimension),error_code=NULL,generated_at=UTC_TIMESTAMP(6),attempts=LEAST(attempts+1,1000000)",model.version(),model.dimension(),encoded,keys[0],keys[1],keys[2],keys[3],settings.get("consent_epoch"),settings.get("erase_epoch"),content);
            }catch(Exception failure){
                if(fence!=null){var now=db.queryForList("SELECT consent_epoch,erase_epoch,mode FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=?",keys[0],keys[1]);if(now.isEmpty() || now.get(0).get("mode").equals("OFF") || !now.get(0).get("consent_epoch").equals(fence.get("consent_epoch")) || !now.get(0).get("erase_epoch").equals(fence.get("erase_epoch")))continue;}
                String code=failure instanceof AgentException a?a.code():"EMBEDDING_UNAVAILABLE";
                if(Set.of("MEMORY_NOT_FOUND","MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE").contains(code))continue;
                db.update("INSERT INTO agent_memory_embedding(tenant_id,owner_user_id,memory_id,version,model_version,dimension,state,next_attempt_at,error_code,attempts) SELECT r.tenant_id,r.owner_user_id,r.memory_id,r.version,?,?,'FAILED',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND),?,1 FROM agent_memory_revision r JOIN agent_memory_item i ON i.tenant_id=r.tenant_id AND i.owner_user_id=r.owner_user_id AND i.memory_id=r.memory_id WHERE r.tenant_id=? AND r.owner_user_id=? AND r.memory_id=? AND r.version=? AND r.content<>'' AND i.status='ACTIVE' ON DUPLICATE KEY UPDATE state=IF(state='READY','READY','FAILED'),next_attempt_at=VALUES(next_attempt_at),error_code=VALUES(error_code),attempts=LEAST(attempts+1,1000000)",model.version(),model.dimension(),policy.retrySeconds(),code,keys[0],keys[1],keys[2],keys[3]);
            }
        }
        db.update("DELETE e FROM agent_memory_embedding e LEFT JOIN agent_memory_revision r ON r.tenant_id=e.tenant_id AND r.owner_user_id=e.owner_user_id AND r.memory_id=e.memory_id AND r.version=e.version WHERE r.memory_id IS NULL OR r.content='' OR (r.expires_at IS NOT NULL AND r.expires_at<=UTC_TIMESTAMP(6))");
    }
    public List<Entry> search(RunState run,String query,long sequence){
        var model=models.getIfAvailable();if(model==null){TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.semantic_state","DISABLED"));return List.of();}
        try{
            var rows=db.queryForList("SELECT e.memory_id,e.version,e.vector_json FROM agent_memory_embedding e JOIN agent_memory_revision r ON r.tenant_id=e.tenant_id AND r.owner_user_id=e.owner_user_id AND r.memory_id=e.memory_id AND r.version=e.version JOIN agent_memory_item i ON i.tenant_id=r.tenant_id AND i.owner_user_id=r.owner_user_id AND i.memory_id=r.memory_id WHERE e.tenant_id=? AND e.owner_user_id=? AND e.model_version=? AND e.dimension=? AND e.state='READY' AND r.scope_type='USER' AND r.valid_from_seq<=? AND (r.valid_to_seq IS NULL OR r.valid_to_seq>?) AND i.status='ACTIVE' AND r.content<>'' AND (r.expires_at IS NULL OR r.expires_at>UTC_TIMESTAMP(6)) AND "+MemorySources.REVISION_READABLE+" ORDER BY r.valid_from_seq DESC LIMIT 1000",run.owner.tenantId(),run.owner.userId(),model.version(),model.dimension(),sequence,sequence);
            if(rows.isEmpty()){TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.semantic_state","COLD","memory.vector_indexed",0));return List.of();}
            run.verifyMemory();var control=run.callControl(Instant.now().plusMillis(policy.queryTimeoutMillis()),run.cancellation());
            var result=model.embed(List.of(query),control);if(result.size()!=1 || result.get(0).size()!=model.dimension())throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid query vector");
            var q=vector(result.get(0));
            record Hit(UUID id,long version,double score){}var hits=new ArrayList<Hit>();
            for(var row:rows){run.checkpoint();var values=json.readTree(row.get("vector_json").toString());if(values.size()!=q.length)continue;var v=new ArrayList<Float>();values.forEach(n->v.add(n.floatValue()));double score=cosine(q,vector(v));if(score>=policy.minSimilarity())hits.add(new Hit(UUID.fromString(row.get("memory_id").toString()),((Number)row.get("version")).longValue(),score));}
            hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h->h.id().toString()));var out=new ArrayList<Entry>();
            for(var h:hits.stream().limit(policy.candidates()).toList())try{out.add(ledger.read(run.owner,h.id(),h.version(),sequence));}catch(AgentException e){if(!e.code().equals("MEMORY_NOT_FOUND"))throw e;}
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.semantic_state","READY","memory.vector_candidates",out.size(),"memory.vector_indexed",rows.size(),"memory.embedding_version",model.version()));return List.copyOf(out);
        }catch(AgentException failure){
            run.checkpoint();run.verifyMemory();
            if(Set.of("CANCELLED","RUN_TERMINATED","MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE","FORBIDDEN").contains(failure.code()))throw failure;
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.semantic_state","DEGRADED","memory.semantic_error",failure.code()));return List.of();
        }catch(java.io.IOException failure){throw new AgentException("STORAGE_CORRUPT","Invalid stored memory vector");}
    }
    static double[] vector(List<Float> input){double[] out=new double[input.size()];double norm=0;for(int i=0;i<out.length;i++){double x=input.get(i);if(!Double.isFinite(x))throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid vector");out[i]=x;norm+=x*x;}if(norm==0)throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Zero vector");double d=Math.sqrt(norm);for(int i=0;i<out.length;i++)out[i]/=d;return out;}
    static double cosine(double[] a,double[] b){double sum=0;for(int i=0;i<a.length;i++)sum+=a[i]*b[i];return sum;}
}
