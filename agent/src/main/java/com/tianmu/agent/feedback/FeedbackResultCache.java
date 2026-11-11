package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import com.tianmu.agent.harness.trace.TraceAttributes;
import io.opentelemetry.api.trace.Span;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Reusable proposals have their own bounded lifetime, never references to a producer run's blobs. */
public final class FeedbackResultCache {
    public record Entry(List<Proposal> proposals,UUID sourceJob,Instant cachedAt){public Entry{proposals=List.copyOf(proposals);}}
    private record Flight(UUID token,Instant until){}
    private final java.util.concurrent.ConcurrentHashMap<String,Flight> flights=new java.util.concurrent.ConcurrentHashMap<>();
    public final class Lease implements AutoCloseable {
        private final String key;private final FeedbackJobStore.Job job;private final Flight flight;private final Entry entry;
        private Lease(String key,FeedbackJobStore.Job job,Flight flight,Entry entry){this.key=key;this.job=job;this.flight=flight;this.entry=entry;}
        public Entry entry(){return entry;}
        public void close(){if(flight==null)return;if(db==null){flights.remove(key,flight);return;}try{db.update("DELETE FROM agent_feedback_cache_flight WHERE cache_key=? AND lease_token=?",key,flight.token().toString());}catch(org.springframework.dao.DataAccessException ignored){/* An abandoned lease expires independently. */}}
    }
    public Lease awaitOrLead(String key,FeedbackJobStore.Job job,com.tianmu.agent.runtime.control.CallControl control){
        long start=System.nanoTime();
        try{while(true){
            control.checkpoint();if(!Instant.now().isBefore(control.deadline()))throw new AgentException("MODEL_CALL_TIMEOUT","Shared analysis wait exceeded deadline");
            Entry cached=get(key,job);if(cached!=null)return new Lease(key,job,null,cached);
            var proposed=new Flight(UUID.randomUUID(),control.deadline().plusSeconds(governance.cacheFlightMarginSeconds()));boolean leader;
            if(db==null)leader=flights.compute(key,(k,old)->old==null || !clock.instant().isBefore(old.until())?proposed:old)==proposed;
            else try{
                db.update("DELETE FROM agent_feedback_cache_flight WHERE lease_until<=UTC_TIMESTAMP(6)");
                leader=db.update("INSERT IGNORE INTO agent_feedback_cache_flight(cache_key,tenant_id,user_id,lease_token,lease_until) VALUES(?,?,?,?,?)",key,job.owner().tenantId(),job.owner().userId(),proposed.token().toString(),java.sql.Timestamp.from(proposed.until()))==1;
            }catch(org.springframework.dao.DataAccessException unavailable){throw new AgentException("STORAGE_UNAVAILABLE","Shared analysis lease unavailable");}
            if(leader){try{return new Lease(key,job,proposed,get(key,job));}catch(RuntimeException failure){new Lease(key,job,proposed,null).close();throw failure;}}
            try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AgentException("CANCELLED","Shared analysis wait interrupted");}
        }}finally{TraceAttributes.apply(Span.current(),Map.of("analysis.cache_wait_ms",(System.nanoTime()-start)/1_000_000));}
    }
    private final ObjectMapper json;private final FeedbackReusePolicy policy;private final JdbcTemplate db;private final TransactionTemplate tx;private final Clock clock;
    private final com.github.benmanes.caffeine.cache.Cache<String,Entry> memory;
    public FeedbackResultCache(ObjectMapper json,FeedbackReusePolicy policy,DataSource source){this(json,policy,source,Clock.systemUTC(),FeedbackRuntimeProperties.defaults());}
    public FeedbackResultCache(ObjectMapper json,FeedbackReusePolicy policy,DataSource source,FeedbackRuntimeProperties governance){this(json,policy,source,Clock.systemUTC(),governance);}
    FeedbackResultCache(ObjectMapper json,FeedbackReusePolicy policy,DataSource source,Clock clock){this(json,policy,source,clock,FeedbackRuntimeProperties.defaults());}
    private final FeedbackRuntimeProperties governance;
    FeedbackResultCache(ObjectMapper json,FeedbackReusePolicy policy,DataSource source,Clock clock,FeedbackRuntimeProperties governance){this.governance=governance;
        this.json=json.copy().findAndRegisterModules().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.policy=policy;this.clock=clock;
        db=source==null?null:new JdbcTemplate(source);if(db!=null)db.setQueryTimeout(governance.queryTimeoutSeconds());
        tx=source==null?null:new TransactionTemplate(new DataSourceTransactionManager(source));if(tx!=null)tx.setTimeout(governance.transactionTimeoutSeconds());
        memory=com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(policy.maxEntries()).expireAfterWrite(policy.window().isZero()?Duration.ofNanos(1):policy.window()).build();
    }
    public boolean enabled(){return policy.enabled() && !policy.window().isZero();}
    public String key(FeedbackJobStore.Job job,Input input){
        if(!enabled())return null;
        var content=new LinkedHashMap<String,Object>();content.put("version","accepted-proposals-v1");content.put("tenant",job.owner().tenantId());content.put("user",job.owner().userId());content.put("video_id",job.request().query().videoId());content.put("sources",job.request().sources().stream().map(Enum::name).sorted().toList());content.put("grounding",job.request().grounding());content.put("model",job.model());content.put("policy",job.policyHash());content.put("kind",input.kind());content.put("rules",job.request().rulesVersion());
        if(FeedbackCompression.RERANK_VERSION.equals(job.request().rulesVersion()))content.put("query",job.request().query());
        if(input.kind()==WorkKind.CLASSIFY){content.put("groups",input.groups());content.put("directory",input.knownTopics());}
        else {content.put("material",FeedbackSynthesis.material(input.children()).payload());if(!input.groups().isEmpty()){content.put("review_groups",input.groups());content.put("review_bindings",input.children().stream().map(Topic::members).toList());}}
        if(input.video()!=null){var v=input.video();content.put("video",List.of(v.sourceVersion(),v.startMs(),v.endMs(),v.summary(),v.moments(),v.coverage(),v.sampledAtMs(),v.transcript(),v.model()));}
        try{return FeedbackObjects.sha(json.writeValueAsBytes(content));}catch(Exception invalid){throw new AgentException("STORAGE_CORRUPT","Cannot identify analysis input");}
    }
    public Entry get(String key,FeedbackJobStore.Job job){
        if(key==null)return null;
        try{
            Entry entry;
            if(db==null)entry=memory.getIfPresent(key);
            else {var rows=db.queryForList("SELECT proposals_json,source_job,cached_at FROM agent_feedback_result_cache WHERE cache_key=? AND tenant_id=? AND user_id=? AND expires_at>UTC_TIMESTAMP(6)",key,job.owner().tenantId(),job.owner().userId());if(rows.isEmpty())return null;
                var row=rows.get(0);try{entry=new Entry(Arrays.asList(json.readValue(row.get("proposals_json").toString(),Proposal[].class)),UUID.fromString(row.get("source_job").toString()),row.get("cached_at") instanceof java.sql.Timestamp at?at.toInstant():((LocalDateTime)row.get("cached_at")).toInstant(ZoneOffset.UTC));}catch(Exception corrupt){invalidate(key,job);return null;}}
            if(entry==null || !clock.instant().isBefore(entry.cachedAt().plus(policy.window())))return null;
            TraceAttributes.apply(Span.current(),Map.of("analysis.cache_age_ms",Math.max(0,Duration.between(entry.cachedAt(),clock.instant()).toMillis()),"analysis.cache_source_job",entry.sourceJob().toString()));return entry;
        }catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException failure){throw new AgentException("STORAGE_UNAVAILABLE","Analysis reuse ledger unavailable");}
    }
    public void put(String key,FeedbackJobStore.Job job,Input input,List<Proposal> proposals){put(key,job,input,proposals,null);}
    public void put(String key,FeedbackJobStore.Job job,Input input,List<Proposal> proposals,Lease lease){
        if(key==null)return;var canonical=canonical(input,proposals,true);String body;
        try{body=json.writeValueAsString(canonical);}catch(Exception invalid){return;}if(body.getBytes(StandardCharsets.UTF_8).length>131072)return;
        var now=clock.instant();var entry=new Entry(canonical,job.id(),now);
        // Cache publication is optional; accepted results remain deliverable if cache storage fails.
        if(db==null){if(lease==null || lease.flight!=null && flights.get(key)==lease.flight && clock.instant().isBefore(lease.flight.until()))memory.put(key,entry);return;}
        try{tx.execute(status->{db.queryForList("SELECT guard_id FROM agent_feedback_cache_guard WHERE guard_id=1 FOR UPDATE");
            if(lease!=null){if(lease.flight==null)return null;var owned=db.queryForList("SELECT lease_token FROM agent_feedback_cache_flight WHERE cache_key=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",key,lease.flight.token().toString());if(owned.isEmpty())return null;}
            db.update("DELETE FROM agent_feedback_result_cache WHERE expires_at<=UTC_TIMESTAMP(6) LIMIT ?",governance.cacheCleanupBatch());
            if(db.queryForObject("SELECT COUNT(*) FROM agent_feedback_result_cache",Long.class)>=policy.maxEntries())db.update("DELETE FROM agent_feedback_result_cache ORDER BY expires_at LIMIT 1");
            db.update("INSERT INTO agent_feedback_result_cache(cache_key,tenant_id,user_id,proposals_json,source_job,cached_at,expires_at) VALUES(?,?,?,CAST(? AS JSON),?,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? MICROSECOND)) ON DUPLICATE KEY UPDATE proposals_json=VALUES(proposals_json),source_job=VALUES(source_job),cached_at=VALUES(cached_at),expires_at=VALUES(expires_at)",key,job.owner().tenantId(),job.owner().userId(),body,job.id().toString(),Math.multiplyExact(policy.window().toMillis(),1000));return null;});}
        catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("analysis_cache_publish_unavailable");}
    }
    public void invalidate(String key,FeedbackJobStore.Job job){if(key==null)return;memory.invalidate(key);if(db!=null)db.update("DELETE FROM agent_feedback_result_cache WHERE cache_key=? AND tenant_id=? AND user_id=?",key,job.owner().tenantId(),job.owner().userId());}
    public static List<Proposal> restore(Input input,Entry entry){return canonical(input,entry.proposals(),false);}
    private static List<Proposal> canonical(Input input,List<Proposal> proposals,boolean saving){
        if(input.kind()!=WorkKind.MERGE)return List.copyOf(proposals);
        var aliases=new HashMap<String,String>();int i=0;for(var child:input.children()){String alias="C"+(++i);aliases.put(saving?child.id():alias,saving?alias:child.id());}
        return proposals.stream().map(p->new Proposal(p.key(),p.title(),p.observation(),p.recommendation(),p.members().stream().map(m->aliases.getOrDefault(m,m)).toList(),p.selection())).toList();
    }
}
