package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.memory.PersonalMemory.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Manual-source ledger. Account row serializes mutations; all eligibility lives in revisions. */
public final class MysqlPersonalMemoryStore implements PersonalMemoryStore {
    private final com.tianmu.agent.memory.MemoryRuntimeProperties governance;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final int quota;
    public MysqlPersonalMemoryStore(DataSource source,ObjectMapper json) {this(source,json,Clock.systemUTC(),1000);}
    public MysqlPersonalMemoryStore(DataSource source,ObjectMapper json,Clock clock,int quota) { this(source, json, clock, quota, com.tianmu.agent.memory.MemoryRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public MysqlPersonalMemoryStore(DataSource source,ObjectMapper json,Clock clock,int quota, com.tianmu.agent.memory.MemoryRuntimeProperties governance) { this.governance=governance;
        if(quota<1 || quota>1000)throw new IllegalArgumentException("Invalid memory quota");
        this.db=new JdbcTemplate(source);db.setQueryTimeout(governance.personalQueryTimeoutSeconds());
        this.tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(governance.personalTransactionTimeoutSeconds());
        this.json=json.copy().findAndRegisterModules();this.clock=clock;this.quota=quota;
    }
    private <T>T call(Supplier<T> action) {
        try{return action.get();}catch(org.springframework.dao.DataAccessException e){throw new AgentException("MEMORY_UNAVAILABLE","Personal memory storage is unavailable");}
    }
    private <T>T write(AccessScope owner,Supplier<T> action) {
        PersonalMemory.owner(owner);
        return call(()->tx.execute(status->{lock(owner);return action.get();}));
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid memory data");}
    }
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private Timestamp now(){return Timestamp.from(clock.instant());}
    private static Timestamp stamp(Instant value){return value==null?null:Timestamp.from(value);}
    private void lock(AccessScope o) {
        db.update("INSERT INTO agent_memory_account(tenant_id,owner_user_id) VALUES(?,?) ON DUPLICATE KEY UPDATE tenant_id=tenant_id",o.tenantId(),o.userId());
        db.queryForObject("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",Long.class,o.tenantId(),o.userId());
    }
    @Override public Settings settings(AccessScope o) {
        PersonalMemory.owner(o);
        return call(()->{
            var rows=db.query("SELECT * FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=?",(r,n)->new Settings(r.getLong("memory_seq"),r.getLong("consent_epoch"),r.getLong("erase_epoch"),r.getLong("settings_version"),Mode.valueOf(r.getString("mode")),r.getBoolean("auto_extract")),o.tenantId(),o.userId());
            return rows.isEmpty()?new Settings(0,0,0,0,Mode.ON,true):rows.get(0);
        });
    }
    @Override public Settings settings(AccessScope o,long expected,Mode mode) {
        if(expected<0 || mode==null)throw PersonalMemory.invalid();
        return write(o,()->{
            var current=settings(o);if(current.version()!=expected)throw PersonalMemory.conflict();
            if(current.mode()!=mode){db.update("UPDATE agent_memory_account SET mode=?,consent_epoch=consent_epoch+1,settings_version=settings_version+1 WHERE tenant_id=? AND owner_user_id=?",mode.name(),o.tenantId(),o.userId());ProjectionChecks.eraseOwner(db,o);}
            return settings(o);
        });
    }
    private void writable(AccessScope o) {
        if(settings(o).mode()!=Mode.ON)throw new AgentException("MEMORY_DISABLED","Memory saving is disabled");
    }
    private long advance(AccessScope o,boolean erase) {
        db.update("UPDATE agent_memory_account SET memory_seq=memory_seq+1,erase_epoch=erase_epoch+? WHERE tenant_id=? AND owner_user_id=?",erase?1:0,o.tenantId(),o.userId());
        if(erase)ProjectionChecks.eraseOwner(db,o);
        return settings(o).sequence();
    }
    private Receipt replay(AccessScope o,String key,String fingerprint) {
        var rows=db.queryForList("SELECT request_hash,receipt_json FROM agent_memory_mutation WHERE tenant_id=? AND owner_user_id=? AND idempotency_key=?",o.tenantId(),o.userId(),key);
        if(rows.isEmpty())return null;
        if(!fingerprint.equals(rows.get(0).get("request_hash")))throw new AgentException("IDEMPOTENCY_CONFLICT","Idempotency key belongs to a different request");
        try{return json.readValue(rows.get(0).get("receipt_json").toString(),Receipt.class);}
        catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid memory receipt");}
    }
    private Receipt commit(AccessScope o,String key,String fingerprint,Receipt receipt) {
        db.update("INSERT INTO agent_memory_mutation(tenant_id,owner_user_id,idempotency_key,request_hash,receipt_json,created_at) VALUES(?,?,?,?,?,?)",o.tenantId(),o.userId(),key,fingerprint,encode(receipt),now());
        return receipt;
    }
    private String fingerprint(String action,UUID id,long expected,Content content) {return hash(encode(Arrays.asList(action,id,expected,content)));}
    private void valid(Content content) {
        if(content==null || content.expiresAt()!=null && !content.expiresAt().isAfter(clock.instant()))throw PersonalMemory.invalid();
    }
    private void validScope(AccessScope o,Content content) {
        if(content.scope().type()==ScopeType.TOPIC && db.queryForObject("SELECT COUNT(*) FROM agent_memory_topic WHERE tenant_id=? AND owner_user_id=? AND topic_id=?",Integer.class,o.tenantId(),o.userId(),content.scope().key())==0)throw PersonalMemory.missing();
        if(content.scope().type()==ScopeType.SESSION)throw PersonalMemory.invalid();
    }
    @Override public Scope scope(AccessScope o,UUID id) {
        PersonalMemory.owner(o);if(id==null)throw PersonalMemory.invalid();
        return call(()->{
            var rows=db.query("SELECT r.scope_type,r.scope_key FROM agent_memory_item i JOIN agent_memory_revision r ON r.tenant_id=i.tenant_id AND r.owner_user_id=i.owner_user_id AND r.memory_id=i.memory_id AND r.version=i.current_version WHERE i.tenant_id=? AND i.owner_user_id=? AND i.memory_id=?",(r,n)->new Scope(ScopeType.valueOf(r.getString(1)),r.getString(2)),o.tenantId(),o.userId(),id.toString());
            if(rows.isEmpty())throw PersonalMemory.missing();return rows.get(0);
        });
    }
    private static final String SELECT="SELECT r.* FROM agent_memory_revision r JOIN agent_memory_item i ON i.tenant_id=r.tenant_id AND i.owner_user_id=r.owner_user_id AND i.memory_id=r.memory_id AND i.current_version=r.version WHERE r.tenant_id=? AND r.owner_user_id=? AND i.status='ACTIVE' AND (r.expires_at IS NULL OR r.expires_at>?) AND "+MemorySources.REVISION_READABLE;
    private Entry entry(ResultSet r,int row) throws SQLException {
        var expiry=r.getTimestamp("expires_at");
        return new Entry(UUID.fromString(r.getString("memory_id")),r.getLong("version"),r.getLong("valid_from_seq"),new Content(new Scope(ScopeType.valueOf(r.getString("scope_type")),r.getString("scope_key")),Kind.valueOf(r.getString("kind")),r.getString("content"),expiry==null?null:expiry.toInstant()),r.getTimestamp("created_at").toInstant());
    }
    @Override public Entry get(AccessScope o,UUID id) {
        PersonalMemory.owner(o);if(id==null)throw PersonalMemory.invalid();
        return call(()->{
            var rows=db.query(SELECT+" AND r.memory_id=?",this::entry,o.tenantId(),o.userId(),now(),id.toString());
            if(rows.isEmpty())throw PersonalMemory.missing();var result=rows.get(0);result.content().scope().authorize(o);return result;
        });
    }
    @Override public Page list(AccessScope o,Scope scope,long before,int limit,String query) {
        PersonalMemory.owner(o);
        if(scope==null || before<1 || limit<1 || limit>50 || query==null || query.getBytes(StandardCharsets.UTF_8).length>256)throw PersonalMemory.invalid();
        scope.authorize(o);
        return call(()->{
            String sql=SELECT+" AND r.scope_type=? AND r.scope_key=? AND r.valid_from_seq<?";
            List<Object> args=new ArrayList<>(List.of(o.tenantId(),o.userId(),now(),scope.type().name(),scope.key(),before));
            if(!query.isBlank()) {
                var terms=MemoryKeywords.terms(query,16);if(terms.isEmpty())return new Page(List.of(),0,false);
                sql+=" AND EXISTS (SELECT 1 FROM agent_memory_keyword k WHERE k.tenant_id=r.tenant_id AND k.owner_user_id=r.owner_user_id AND k.memory_id=r.memory_id AND k.version=r.version AND k.term IN ("+String.join(",",Collections.nCopies(terms.size(),"?"))+"))";
                args.addAll(terms);
            }
            sql+=" ORDER BY r.valid_from_seq DESC LIMIT ?";args.add(limit+1);
            var rows=db.query(sql,this::entry,args.toArray());boolean more=rows.size()>limit;
            var selected=rows.subList(0,Math.min(rows.size(),limit));
            return new Page(selected,more?selected.get(selected.size()-1).sequence():0,more);
        });
    }
    private void revision(AccessScope o,UUID id,long version,long seq,Content c) {
        UUID source=UUID.randomUUID();
        db.update("INSERT INTO agent_memory_source(tenant_id,owner_user_id,source_id,source_kind,declaration,content_hash,created_at) VALUES(?,?,?,'MANUAL',?,?,?)",o.tenantId(),o.userId(),source.toString(),c.text(),hash(c.text()),now());
        db.update("INSERT INTO agent_memory_revision(tenant_id,owner_user_id,memory_id,version,valid_from_seq,scope_type,scope_key,kind,content,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",o.tenantId(),o.userId(),id.toString(),version,seq,c.scope().type().name(),c.scope().key(),c.kind().name(),c.text(),stamp(c.expiresAt()),now());
        db.update("INSERT INTO agent_memory_dependency(tenant_id,owner_user_id,memory_id,version,source_id) VALUES(?,?,?,?,?)",o.tenantId(),o.userId(),id.toString(),version,source.toString());
        for(String term:MemoryKeywords.terms(c.text(),1024))db.update("INSERT INTO agent_memory_keyword(tenant_id,owner_user_id,memory_id,version,term) VALUES(?,?,?,?,?)",o.tenantId(),o.userId(),id.toString(),version,term);
    }
    @Override public Receipt create(AccessScope o,Content content,String key) {
        PersonalMemory.key(key);if(content==null)throw PersonalMemory.invalid();content.scope().authorize(o);
        String fingerprint=fingerprint("CREATE",null,0,content);
        return write(o,()->{
            Receipt prior=replay(o,key,fingerprint);if(prior!=null)return prior;writable(o);valid(content);validScope(o,content);
            Long active=db.queryForObject("SELECT COUNT(*) FROM agent_memory_item i JOIN agent_memory_revision r ON r.tenant_id=i.tenant_id AND r.owner_user_id=i.owner_user_id AND r.memory_id=i.memory_id AND r.version=i.current_version WHERE i.tenant_id=? AND i.owner_user_id=? AND i.status='ACTIVE' AND (r.expires_at IS NULL OR r.expires_at>?)",Long.class,o.tenantId(),o.userId(),now());
            if(active>=quota)throw new AgentException("MEMORY_QUOTA_EXCEEDED","Personal memory quota reached");
            long seq=advance(o,false);UUID id=UUID.randomUUID();
            db.update("INSERT INTO agent_memory_item(tenant_id,owner_user_id,memory_id,current_version,created_at) VALUES(?,?,?,1,?)",o.tenantId(),o.userId(),id.toString(),now());
            revision(o,id,1,seq,content);return commit(o,key,fingerprint,new Receipt(id,1,seq,"CREATED"));
        });
    }
    @Override public Receipt update(AccessScope o,UUID id,long expected,Content content,String key) {
        PersonalMemory.key(key);if(id==null || expected<1 || content==null)throw PersonalMemory.invalid();content.scope().authorize(o);
        String fingerprint=fingerprint("UPDATE",id,expected,content);
        return write(o,()->{
            Receipt prior=replay(o,key,fingerprint);if(prior!=null)return prior;writable(o);valid(content);validScope(o,content);
            Entry current=get(o,id);if(current.version()!=expected)throw PersonalMemory.conflict();
            long seq=advance(o,false),version=expected+1;
            db.update("UPDATE agent_memory_revision SET valid_to_seq=? WHERE tenant_id=? AND owner_user_id=? AND memory_id=? AND version=?",seq,o.tenantId(),o.userId(),id.toString(),expected);
            revision(o,id,version,seq,content);
            db.update("UPDATE agent_memory_item SET current_version=? WHERE tenant_id=? AND owner_user_id=? AND memory_id=?",version,o.tenantId(),o.userId(),id.toString());
            return commit(o,key,fingerprint,new Receipt(id,version,seq,"UPDATED"));
        });
    }
    private void erase(AccessScope o,UUID id,long seq,long epoch) {
        // Keep only opaque fact keys as tombstones; remove derived descriptive metadata.
        db.update("UPDATE agent_memory_fact SET identity_json=JSON_OBJECT(),value_hash=REPEAT('0',64) WHERE tenant_id=? AND owner_user_id=? AND memory_id=?",o.tenantId(),o.userId(),id.toString());
        db.update("UPDATE agent_memory_candidate SET details_json=JSON_OBJECT() WHERE tenant_id=? AND owner_user_id=? AND memory_id=?",o.tenantId(),o.userId(),id.toString());
        db.update("DELETE FROM agent_memory_index WHERE tenant_id=? AND owner_user_id=? AND memory_id=?",o.tenantId(),o.userId(),id.toString());
        db.update("DELETE FROM agent_memory_embedding WHERE tenant_id=? AND owner_user_id=? AND memory_id=?",o.tenantId(),o.userId(),id.toString());
        // Both source declarations and every historical body are erased in the same transaction.
        db.update("UPDATE agent_memory_source s JOIN agent_memory_dependency d ON d.tenant_id=s.tenant_id AND d.owner_user_id=s.owner_user_id AND d.source_id=s.source_id SET s.declaration='',s.content_hash=?,s.status='DELETED' WHERE d.tenant_id=? AND d.owner_user_id=? AND d.memory_id=?",hash(""),o.tenantId(),o.userId(),id.toString());
        db.update("UPDATE agent_memory_revision SET content='',valid_to_seq=COALESCE(valid_to_seq,?) WHERE tenant_id=? AND owner_user_id=? AND memory_id=?",seq,o.tenantId(),o.userId(),id.toString());
        db.update("DELETE FROM agent_memory_keyword WHERE tenant_id=? AND owner_user_id=? AND memory_id=?",o.tenantId(),o.userId(),id.toString());
        db.update("UPDATE agent_memory_item SET status='DELETED' WHERE tenant_id=? AND owner_user_id=? AND memory_id=?",o.tenantId(),o.userId(),id.toString());
        db.update("INSERT INTO agent_memory_tombstone(tenant_id,owner_user_id,target_id,erase_epoch,created_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE erase_epoch=VALUES(erase_epoch)",o.tenantId(),o.userId(),id.toString(),epoch,now());
    }
    @Override public Receipt delete(AccessScope o,UUID id,long expected,String key) {
        PersonalMemory.key(key);if(id==null || expected<1)throw PersonalMemory.invalid();String fingerprint=fingerprint("DELETE",id,expected,null);
        return write(o,()->{
            scope(o,id).authorize(o);Receipt prior=replay(o,key,fingerprint);if(prior!=null)return prior;
            // Expiry prevents recall, but must not prevent the owner from erasing the stored body.
            var versions=db.queryForList("SELECT current_version FROM agent_memory_item WHERE tenant_id=? AND owner_user_id=? AND memory_id=? AND status='ACTIVE'",Long.class,o.tenantId(),o.userId(),id.toString());
            if(versions.isEmpty())throw PersonalMemory.missing();if(versions.get(0)!=expected)throw PersonalMemory.conflict();
            long seq=advance(o,true);erase(o,id,seq,settings(o).eraseEpoch());
            return commit(o,key,fingerprint,new Receipt(id,expected,seq,"DELETED"));
        });
    }
    @Override public Receipt reset(AccessScope o,String key) {
        PersonalMemory.key(key);String fingerprint=fingerprint("RESET",null,0,null);
        return write(o,()->{
            Receipt prior=replay(o,key,fingerprint);if(prior!=null)return prior;
            long seq=advance(o,true),epoch=settings(o).eraseEpoch();
            // Old epochs cannot publish; new consent may learn fresh facts after a reset.
            db.update("DELETE FROM agent_memory_fact WHERE tenant_id=? AND owner_user_id=?",o.tenantId(),o.userId());
            db.update("DELETE FROM agent_memory_candidate WHERE tenant_id=? AND owner_user_id=?",o.tenantId(),o.userId());
            db.update("DELETE FROM agent_memory_index WHERE tenant_id=? AND owner_user_id=?",o.tenantId(),o.userId());
            db.update("DELETE FROM agent_memory_embedding WHERE tenant_id=? AND owner_user_id=?",o.tenantId(),o.userId());
            db.update("DELETE FROM agent_memory_recall_manifest WHERE tenant_id=? AND owner_user_id=?",o.tenantId(),o.userId());
            db.update("DELETE FROM agent_memory_dream WHERE tenant_id=? AND owner_user_id=?",o.tenantId(),o.userId());
            // Set-based reset stays bounded even at the account quota and with many revisions.
            db.update("INSERT INTO agent_memory_tombstone(tenant_id,owner_user_id,target_id,erase_epoch,created_at) SELECT tenant_id,owner_user_id,memory_id,?,? FROM agent_memory_item WHERE tenant_id=? AND owner_user_id=? AND status='ACTIVE' ON DUPLICATE KEY UPDATE erase_epoch=VALUES(erase_epoch)",epoch,now(),o.tenantId(),o.userId());
            db.update("UPDATE agent_memory_source SET declaration='',content_hash=?,status='DELETED' WHERE tenant_id=? AND owner_user_id=?",hash(""),o.tenantId(),o.userId());
            db.update("UPDATE agent_memory_revision SET content='',valid_to_seq=COALESCE(valid_to_seq,?) WHERE tenant_id=? AND owner_user_id=?",seq,o.tenantId(),o.userId());
            db.update("DELETE FROM agent_memory_keyword WHERE tenant_id=? AND owner_user_id=?",o.tenantId(),o.userId());
            db.update("UPDATE agent_memory_item SET status='DELETED' WHERE tenant_id=? AND owner_user_id=? AND status='ACTIVE'",o.tenantId(),o.userId());
            return commit(o,key,fingerprint,new Receipt(new UUID(0,0),0,seq,"RESET"));
        });
    }
}
