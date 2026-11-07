package com.tianmu.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.tianmu.agent.runtime.RunState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Durable source and request boundaries. Stored snapshots contain references, never memory bodies. */
public final class MemoryLedger {
    private final com.tianmu.agent.memory.MemoryRuntimeProperties governance;
    public record Ref(UUID id,long version,long sequence,boolean initial){public Ref(UUID id,long version){this(id,version,0,false);}}
    public record Boundary(long sequence,long consent,long erase,Mode mode,boolean consumed,boolean recalled,List<Ref> refs){}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final PersonalMemoryStore store;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private org.springframework.beans.factory.ObjectProvider<com.tianmu.agent.query.VideoPagePort> videoPages;
    public MemoryLedger(DataSource source,PersonalMemoryStore store,ObjectMapper json) { this(source, store, json, com.tianmu.agent.memory.MemoryRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public MemoryLedger(DataSource source,PersonalMemoryStore store,ObjectMapper json, com.tianmu.agent.memory.MemoryRuntimeProperties governance) { this.governance=governance;
        db=new JdbcTemplate(source);db.setQueryTimeout(governance.queryTimeoutSeconds());tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(governance.transactionTimeoutSeconds());this.store=store;this.json=json.copy().findAndRegisterModules();
    }
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid memory ledger");}}
    public Boundary boundary(AccessScope o,UUID run){
        var rows=db.queryForList("SELECT * FROM agent_memory_run WHERE tenant_id=? AND owner_user_id=? AND run_id=?",o.tenantId(),o.userId(),run.toString());
        if(rows.isEmpty())return null;var r=rows.get(0);
        try{return new Boundary(((Number)r.get("memory_seq")).longValue(),((Number)r.get("consent_epoch")).longValue(),((Number)r.get("erase_epoch")).longValue(),Mode.valueOf(r.get("mode").toString()),truth(r.get("consumed")),truth(r.get("recalled")),json.readValue(r.get("selected_json").toString(),new TypeReference<List<Ref>>(){}));}
        catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid memory snapshot");}
    }
    public void begin(RunState run){var s=store.settings(run.owner);db.update("INSERT INTO agent_memory_run(tenant_id,owner_user_id,run_id,session_id,memory_seq,consent_epoch,erase_epoch,mode,selected_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE run_id=run_id",run.owner.tenantId(),run.owner.userId(),run.id.toString(),run.sessionId.toString(),s.sequence(),s.consentEpoch(),s.eraseEpoch(),s.mode().name(),"[]");}
    public Entry read(AccessScope o,UUID id,long version,long sequence){
        live(o,store.scope(o,id));
        var rows=db.query("SELECT r.* FROM agent_memory_revision r JOIN agent_memory_item i ON i.tenant_id=r.tenant_id AND i.owner_user_id=r.owner_user_id AND i.memory_id=r.memory_id WHERE r.tenant_id=? AND r.owner_user_id=? AND r.memory_id=? AND r.version=? AND r.valid_from_seq<=? AND (r.valid_to_seq IS NULL OR r.valid_to_seq>?) AND r.content<>'' AND i.status='ACTIVE' AND (r.expires_at IS NULL OR r.expires_at>UTC_TIMESTAMP(6)) AND "+MemorySources.REVISION_READABLE,
            (r,n)->new Entry(id,r.getLong("version"),r.getLong("valid_from_seq"),new Content(new Scope(ScopeType.valueOf(r.getString("scope_type")),r.getString("scope_key")),Kind.valueOf(r.getString("kind")),r.getString("content"),r.getTimestamp("expires_at")==null?null:r.getTimestamp("expires_at").toInstant()),r.getTimestamp("created_at").toInstant()),o.tenantId(),o.userId(),id.toString(),version,sequence,sequence);
        if(rows.isEmpty())throw PersonalMemory.missing();var result=rows.get(0);live(o,result.content().scope());return result;
    }
    public List<Entry> candidates(AccessScope o,Scope scope,long sequence,String query,int limit){
        scope.authorize(o);var terms=MemoryKeywords.terms(query,16);var args=new ArrayList<Object>(List.of(o.tenantId(),o.userId(),scope.type().name(),scope.key(),sequence,sequence));
        String sql="SELECT r.memory_id,r.version FROM agent_memory_revision r WHERE r.tenant_id=? AND r.owner_user_id=? AND r.scope_type=? AND r.scope_key=? AND r.valid_from_seq<=? AND (r.valid_to_seq IS NULL OR r.valid_to_seq>?)";
        if(!query.isBlank()){
            if(terms.isEmpty())return List.of();sql+=" AND EXISTS(SELECT 1 FROM agent_memory_keyword k WHERE k.tenant_id=r.tenant_id AND k.owner_user_id=r.owner_user_id AND k.memory_id=r.memory_id AND k.version=r.version AND k.term IN ("+String.join(",",Collections.nCopies(terms.size(),"?"))+"))";args.addAll(terms);
        }
        sql+=" ORDER BY r.valid_from_seq DESC LIMIT ?";args.add(Math.min(governance.candidateFetchLimit(),limit));var refs=db.query(sql,(r,n)->new Ref(UUID.fromString(r.getString(1)),r.getLong(2)),args.toArray());
        var out=new ArrayList<Entry>();for(var ref:refs)try{out.add(read(o,ref.id(),ref.version(),sequence));}catch(AgentException e){if(!e.code().equals("MEMORY_NOT_FOUND"))throw e;}return List.copyOf(out);
    }
    public void consume(RunState run,List<Entry> entries){
        consume(run,entries,false);
    }
    public void consume(RunState run,List<Entry> entries,boolean initial){
        var b=boundary(run.owner,run.id);var refs=new LinkedHashSet<Ref>(b.refs());entries.forEach(e->refs.add(new Ref(e.id(),e.version(),b.sequence(),initial)));
        if(refs.stream().map(r->r.id()+":"+r.version()).distinct().count()>32)throw new AgentException("MEMORY_QUOTA_EXCEEDED","Memory reads exceed this turn's budget");
        db.update("UPDATE agent_memory_run SET selected_json=?,consumed=? WHERE tenant_id=? AND owner_user_id=? AND run_id=?",encode(refs),refs.isEmpty()?0:1,run.owner.tenantId(),run.owner.userId(),run.id.toString());
    }
    public void recalled(RunState run){db.update("UPDATE agent_memory_run SET recalled=1 WHERE tenant_id=? AND owner_user_id=? AND run_id=?",run.owner.tenantId(),run.owner.userId(),run.id.toString());}
    /** Initial references and the empty/non-empty recalled marker commit atomically. */
    public List<Entry> commitRecall(RunState run,List<Entry> entries){return tx.execute(status->{
        db.queryForList("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",run.owner.tenantId(),run.owner.userId());
        db.queryForList("SELECT run_id FROM agent_memory_run WHERE tenant_id=? AND owner_user_id=? AND run_id=? FOR UPDATE",run.owner.tenantId(),run.owner.userId(),run.id.toString());
        var b=boundary(run.owner,run.id);if(b==null)throw invalidated();
        if(b.recalled())return restoreInitial(run);
        var now=store.settings(run.owner);
        if(now.consentEpoch()!=b.consent() || now.eraseEpoch()!=b.erase() || now.mode()!=b.mode())throw invalidated();
        for(var entry:entries)read(run.owner,entry.id(),entry.version(),b.sequence());
        consume(run,entries,true);recalled(run);return List.copyOf(entries);
    });}
    public List<Entry> restoreInitial(RunState run){
        guard(run);var b=boundary(run.owner,run.id);if(b==null || !b.recalled())throw invalidated();
        return b.refs().stream().filter(Ref::initial).map(r->read(run.owner,r.id(),r.version(),r.sequence()==0?b.sequence():r.sequence())).toList();
    }
    public List<UUID> sourceRefs(AccessScope owner,Entry entry){
        return db.query("SELECT d.source_id FROM agent_memory_dependency d JOIN agent_memory_source s ON s.tenant_id=d.tenant_id AND s.owner_user_id=d.owner_user_id AND s.source_id=d.source_id WHERE d.tenant_id=? AND d.owner_user_id=? AND d.memory_id=? AND d.version=? AND "+MemorySources.READABLE+" ORDER BY d.source_id",(r,n)->UUID.fromString(r.getString(1)),owner.tenantId(),owner.userId(),entry.id().toString(),entry.version());
    }
    public List<MemoryTypes.Message> inherit(RunState run,List<MemoryTypes.Message> messages){
        var current=boundary(run.owner,run.id);if(current==null)return messages;var refs=new LinkedHashSet<Ref>(current.refs());var result=new ArrayList<MemoryTypes.Message>();
        for(var message:messages){
            if(!"assistant".equalsIgnoreCase(message.role())){result.add(message);continue;}
            var previous=boundary(run.owner,message.runId());boolean allowed=previous==null || !previous.consumed() || historyAllowed(run.owner,message.runId());
            allowed=allowed && ProjectionChecks.allowed(db,json,run.owner,message.runId());
            if(allowed && previous!=null && previous.consumed()){
                var combined=new LinkedHashSet<>(refs);for(var ref:previous.refs())combined.add(new Ref(ref.id(),ref.version(),ref.sequence()==0?previous.sequence():ref.sequence(),false));
                if(combined.stream().map(r->r.id()+":"+r.version()).distinct().count()>32)allowed=false;else refs=combined;
            }
            if(allowed)ProjectionChecks.attach(db,json,run.owner,run.id,ProjectionChecks.refs(db,json,run.owner,message.runId()));
            result.add(allowed?message:new MemoryTypes.Message(message.id(),message.runId(),message.sequence(),message.role(),"[先前回答的记忆依赖不可用，请重新读取或提问]",message.at()));
        }
        db.update("UPDATE agent_memory_run SET selected_json=?,consumed=? WHERE tenant_id=? AND owner_user_id=? AND run_id=?",encode(refs),refs.isEmpty()?0:1,run.owner.tenantId(),run.owner.userId(),run.id.toString());
        return List.copyOf(result);
    }
    public void guard(RunState run){
        if(!historyAllowed(run.owner,run.id))throw invalidated();
    }
    /** Background readers reauthorize original resources without inheriting unrelated assistant claims. */
    public void verifyOriginResources(AccessScope owner,UUID source){
        var rows=db.queryForList("SELECT access_scope_json FROM agent_run WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND data_expires_at>UTC_TIMESTAMP(6)",String.class,owner.tenantId(),owner.userId(),source.toString());
        if(rows.isEmpty())throw invalidated();
        try{var saved=json.readValue(rows.get(0),AccessScope.class);for(long video:saved.readableVideoIds())live(saved,new Scope(ScopeType.VIDEO,Long.toString(video)));}
        catch(java.io.IOException e){throw invalidated();}
    }
    public boolean historyAllowed(AccessScope o,UUID run){
        if(!historyAllowed(db,json,o,run))return false;var b=boundary(o,run);if(b==null || !b.consumed())return true;
        try{for(var ref:b.refs())read(o,ref.id(),ref.version(),ref.sequence()==0?b.sequence():ref.sequence());return true;}
        catch(AgentException e){if(Set.of("MEMORY_NOT_FOUND","MEMORY_SOURCE_UNAVAILABLE","FORBIDDEN","VIDEO_NOT_FOUND","BUSINESS_UNAVAILABLE","BUSINESS_TIMEOUT").contains(e.code()))return false;throw e;}
    }
    private void live(AccessScope owner,Scope scope){
        scope.authorize(owner);if(scope.type()!=ScopeType.VIDEO)return;
        var port=videoPages==null?null:videoPages.getIfAvailable();if(port==null)throw new AgentException("MEMORY_SOURCE_UNAVAILABLE","Live video authorization is unavailable");
        var page=port.page(Long.parseLong(scope.key()),owner);if(page==null || !scope.key().equals(page.videoId()))throw PersonalMemory.missing();
    }
    public Receipt declare(RunState run,Content content,String key,boolean explicit){
        var boundary=boundary(run.owner,run.id);if(boundary==null){begin(run);boundary=boundary(run.owner,run.id);}
        return declare(run.owner,run.sessionId,run.id,content,key,explicit,boundary.consent(),boundary.erase(),()->run.cancellation().checkpoint());
    }
    public Receipt declare(AccessScope o,UUID session,UUID run,Content content,String key,boolean explicit){
        return declare(o,session,run,content,key,explicit,null,null);
    }
    public Receipt declare(AccessScope o,UUID session,UUID run,Content content,String key,boolean explicit,Long consent,Long erase){
        return declare(o,session,run,content,key,explicit,consent,erase,()->{});
    }
    public Receipt declare(AccessScope o,UUID session,UUID run,Content content,String key,boolean explicit,Long consent,Long erase,Runnable authority){
        return tx.execute(status->{
            mutationBoundary(o,session,run,explicit,consent,erase);
            authority.run();
            var receipt=store.create(o,content,key);
            db.update("UPDATE agent_memory_source s JOIN agent_memory_dependency d ON d.tenant_id=s.tenant_id AND d.owner_user_id=s.owner_user_id AND d.source_id=s.source_id SET s.source_kind=?,s.origin_session_id=?,s.origin_run_id=?,s.source_expires_at=(SELECT data_expires_at FROM agent_run WHERE tenant_id=? AND run_id=?) WHERE d.tenant_id=? AND d.owner_user_id=? AND d.memory_id=? AND d.version=? AND s.status='ACTIVE'",explicit?"EXPLICIT_DECLARATION":"AUTO_EXTRACTED",session.toString(),run.toString(),o.tenantId(),run.toString(),o.tenantId(),o.userId(),receipt.id().toString(),receipt.version());return receipt;
        });
    }
    private void mutationBoundary(AccessScope o,UUID session,UUID run,boolean explicit,Long consent,Long erase){
        var sessions=db.queryForList("SELECT session_id,current_run_id,generation FROM agent_session WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND status='OPEN' AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE",o.tenantId(),o.userId(),session.toString());
        if(sessions.isEmpty())throw new AgentException("MEMORY_SOURCE_UNAVAILABLE","Memory source is unavailable");
        var runs=db.queryForList("SELECT run_id,status,session_generation FROM agent_run WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND run_id=? AND data_expires_at>UTC_TIMESTAMP(6) FOR UPDATE",o.tenantId(),o.userId(),session.toString(),run.toString());
        if(runs.isEmpty())throw new AgentException("MEMORY_SOURCE_UNAVAILABLE","Memory source is unavailable");
        if(explicit && (!"RUNNING".equals(runs.get(0).get("status")) || !run.toString().equals(Objects.toString(sessions.get(0).get("current_run_id"),"")) || !Objects.equals(sessions.get(0).get("generation"),runs.get(0).get("session_generation"))))throw new AgentException("RUN_TERMINATED","Request no longer owns this session");
        db.update("INSERT INTO agent_memory_account(tenant_id,owner_user_id) VALUES(?,?) ON DUPLICATE KEY UPDATE tenant_id=tenant_id",o.tenantId(),o.userId());
        db.queryForList("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",o.tenantId(),o.userId());
        var current=store.settings(o);if(consent!=null && (current.consentEpoch()!=consent || current.eraseEpoch()!=erase || !explicit && !current.autoExtract()))throw invalidated();
    }
    public Receipt forget(RunState run,UUID id,long version,String key){
        var b=boundary(run.owner,run.id);if(b==null)throw invalidated();
        return tx.execute(status->{mutationBoundary(run.owner,run.sessionId,run.id,true,b.consent(),b.erase());run.cancellation().checkpoint();var receipt=store.delete(run.owner,id,version,key);
            db.update("UPDATE agent_memory_run SET selected_json='[]',consumed=0 WHERE tenant_id=? AND owner_user_id=? AND run_id=?",run.owner.tenantId(),run.owner.userId(),run.id.toString());return receipt;});
    }
    public Receipt correct(RunState run,UUID id,long version,Content content,String key){
        var b=boundary(run.owner,run.id);if(b==null)throw invalidated();
        return tx.execute(status->{mutationBoundary(run.owner,run.sessionId,run.id,true,b.consent(),b.erase());run.cancellation().checkpoint();
            read(run.owner,id,version,b.sequence());
            var receipt=store.update(run.owner,id,version,content,key);
            db.update("UPDATE agent_memory_source s JOIN agent_memory_dependency d ON d.tenant_id=s.tenant_id AND d.owner_user_id=s.owner_user_id AND d.source_id=s.source_id SET s.source_kind='EXPLICIT_DECLARATION',s.origin_session_id=?,s.origin_run_id=? WHERE d.tenant_id=? AND d.owner_user_id=? AND d.memory_id=? AND d.version=? AND s.status='ACTIVE'",run.sessionId.toString(),run.id.toString(),run.owner.tenantId(),run.owner.userId(),id.toString(),receipt.version());
            return receipt;});
    }
    public static void verifyCommit(JdbcTemplate db,ObjectMapper json,AccessScope owner,UUID run){
        var rows=db.queryForList("SELECT consent_epoch,erase_epoch,mode FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",owner.tenantId(),owner.userId());
        var snapshots=db.queryForList("SELECT consent_epoch,erase_epoch,consumed FROM agent_memory_run WHERE tenant_id=? AND owner_user_id=? AND run_id=?",owner.tenantId(),owner.userId(),run.toString());
        if(!ProjectionChecks.allowed(db,json,owner,run))throw invalidated();
        if(snapshots.isEmpty() || !truth(snapshots.get(0).get("consumed")))return;
        if(rows.isEmpty() || "OFF".equals(rows.get(0).get("mode")) || !Objects.equals(rows.get(0).get("consent_epoch"),snapshots.get(0).get("consent_epoch")) || !Objects.equals(rows.get(0).get("erase_epoch"),snapshots.get(0).get("erase_epoch")) || !historyAllowed(db,json,owner,run))throw invalidated();
    }
    public List<Map<String,Object>> topics(AccessScope o){return db.queryForList("SELECT topic_id AS id,name FROM agent_memory_topic WHERE tenant_id=? AND owner_user_id=? ORDER BY name LIMIT ?",o.tenantId(),o.userId(),governance.topicListLimit());}
    public UUID topic(AccessScope o,String name){
        if(name==null || name.isBlank() || name.codePointCount(0,name.length())>40)throw PersonalMemory.invalid();
        String normalized=java.text.Normalizer.normalize(name.strip(),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);UUID id=UUID.nameUUIDFromBytes((o.tenantId()+"\u0000"+o.userId()+"\u0000"+normalized).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        db.update("INSERT INTO agent_memory_topic(tenant_id,owner_user_id,topic_id,name,name_hash) VALUES(?,?,?,?,SHA2(?,256)) ON DUPLICATE KEY UPDATE topic_id=topic_id",o.tenantId(),o.userId(),id.toString(),name.strip(),normalized);return id;
    }
    public void auto(AccessScope o,long version,boolean enabled){tx.executeWithoutResult(status->{store.settings(o,version,store.settings(o).mode());db.update("UPDATE agent_memory_account SET auto_extract=?,consent_epoch=consent_epoch+1,settings_version=settings_version+1 WHERE tenant_id=? AND owner_user_id=?",enabled?1:0,o.tenantId(),o.userId());});}
    public static AgentException invalidated(){return new AgentException("MEMORY_CONTEXT_INVALIDATED","记忆已更新、遗忘或暂停，请重新提问。");}
    private static boolean truth(Object value){return value instanceof Boolean b?b:value instanceof Number n && n.intValue()!=0;}
    public static boolean historyAllowed(JdbcTemplate db,ObjectMapper json,AccessScope o,UUID run){
        return ProjectionChecks.allowed(db,json,o,run) && memoryOnlyAllowed(db,json,o,run);
    }
    static boolean memoryOnlyAllowed(JdbcTemplate db,ObjectMapper json,AccessScope o,UUID run){
        var rows=db.queryForList("SELECT m.*,a.mode AS current_mode,a.consent_epoch AS current_consent,a.erase_epoch AS current_erase FROM agent_memory_run m LEFT JOIN agent_memory_account a ON a.tenant_id=m.tenant_id AND a.owner_user_id=m.owner_user_id WHERE m.tenant_id=? AND m.owner_user_id=? AND m.run_id=?",o.tenantId(),o.userId(),run.toString());
        if(rows.isEmpty() || !truth(rows.get(0).get("consumed")))return true;var row=rows.get(0);
        if(row.get("current_mode")==null || "OFF".equals(row.get("current_mode")) || !Objects.equals(row.get("consent_epoch"),row.get("current_consent")) || !Objects.equals(row.get("erase_epoch"),row.get("current_erase")))return false;
        try {
            var refs=json.readValue(row.get("selected_json").toString(),new TypeReference<List<Ref>>(){});
            for(var ref:refs){
                var sources=db.queryForList("SELECT r.scope_type,r.scope_key FROM agent_memory_revision r JOIN agent_memory_item i ON i.tenant_id=r.tenant_id AND i.owner_user_id=r.owner_user_id AND i.memory_id=r.memory_id JOIN agent_memory_dependency d ON d.tenant_id=r.tenant_id AND d.owner_user_id=r.owner_user_id AND d.memory_id=r.memory_id AND d.version=r.version JOIN agent_memory_source s ON s.tenant_id=d.tenant_id AND s.owner_user_id=d.owner_user_id AND s.source_id=d.source_id WHERE r.tenant_id=? AND r.owner_user_id=? AND r.memory_id=? AND r.version=? AND r.content<>'' AND i.status='ACTIVE' AND (r.expires_at IS NULL OR r.expires_at>UTC_TIMESTAMP(6)) AND "+MemorySources.READABLE+" AND "+MemorySources.REVISION_READABLE,o.tenantId(),o.userId(),ref.id().toString(),ref.version());
                if(sources.isEmpty())return false;
                for(var source:sources)if("VIDEO".equals(source.get("scope_type")) && !o.readableVideoIds().contains(Long.parseLong(source.get("scope_key").toString())))return false;
            }
            return true;
        }catch(java.io.IOException e){throw new AgentException("STORAGE_CORRUPT","Invalid derived history memory references");}
    }
}
