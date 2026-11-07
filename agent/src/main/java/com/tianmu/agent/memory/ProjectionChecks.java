package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** References only. All dependent source messages must remain readable; follows older derived answers. */
public final class ProjectionChecks {
    private ProjectionChecks(){}
    public static List<String> refs(JdbcTemplate db,ObjectMapper json,AccessScope owner,UUID run){
        var rows=db.queryForList("SELECT pending_json FROM agent_memory_run WHERE tenant_id=? AND owner_user_id=? AND run_id=?",String.class,owner.tenantId(),owner.userId(),run.toString());
        if(rows.isEmpty() || rows.get(0)==null)return List.of();
        try{var array=json.readTree(rows.get(0)).path("compaction_refs");if(array.isMissingNode())return List.of();if(!array.isArray() || array.size()>16)throw MemoryLedger.invalidated();var result=new ArrayList<String>();for(var item:array){String id=item.asText();UUID.fromString(id);result.add(id);}return List.copyOf(result);}
        catch(java.io.IOException|IllegalArgumentException e){throw MemoryLedger.invalidated();}
    }
    public static void attach(JdbcTemplate db,ObjectMapper json,AccessScope owner,UUID run,Collection<String> additional){
        var combined=new LinkedHashSet<>(refs(db,json,owner,run));combined.addAll(additional);if(combined.size()>16)throw new AgentException("MODEL_CONTEXT_TOO_LARGE","Too many summary dependencies");
        try{var state=pending(db,json,owner,run);state.set("compaction_refs",json.valueToTree(combined));save(db,json,owner,run,state);}
        catch(java.io.IOException e){throw MemoryLedger.invalidated();}
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode pending(JdbcTemplate db,ObjectMapper json,AccessScope owner,UUID run)throws java.io.IOException{
        var rows=db.queryForList("SELECT pending_json FROM agent_memory_run WHERE tenant_id=? AND owner_user_id=? AND run_id=?",String.class,owner.tenantId(),owner.userId(),run.toString());
        return rows.isEmpty() || rows.get(0)==null?json.createObjectNode():(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(rows.get(0));
    }
    private static void save(JdbcTemplate db,ObjectMapper json,AccessScope owner,UUID run,Object state)throws java.io.IOException{db.update("UPDATE agent_memory_run SET pending_json=? WHERE tenant_id=? AND owner_user_id=? AND run_id=?",json.writeValueAsString(state),owner.tenantId(),owner.userId(),run.toString());}
    public static List<String> initial(JdbcTemplate db,ObjectMapper json,AccessScope owner,UUID run){try{var state=pending(db,json,owner,run);if(!state.has("initial_compaction_refs"))return null;var result=new ArrayList<String>();state.path("initial_compaction_refs").forEach(id->result.add(id.asText()));return List.copyOf(result);}catch(java.io.IOException e){throw MemoryLedger.invalidated();}}
    public static void initial(JdbcTemplate db,ObjectMapper json,AccessScope owner,UUID run,List<String> ids){try{var state=pending(db,json,owner,run);state.set("initial_compaction_refs",json.valueToTree(ids));save(db,json,owner,run,state);}catch(java.io.IOException e){throw MemoryLedger.invalidated();}}
    public static Set<UUID> sources(JdbcTemplate db,ObjectMapper json,AccessScope owner,Collection<String> initial){
        var queue=new ArrayDeque<>(initial);var visited=new HashSet<String>();var runs=new LinkedHashSet<UUID>();
        while(!queue.isEmpty()){
            String id=queue.removeFirst();if(!visited.add(id))continue;if(visited.size()>128)throw MemoryLedger.invalidated();
            var projections=db.queryForList("SELECT p.message_count FROM agent_memory_projection p JOIN agent_memory_account a ON a.tenant_id=p.tenant_id AND a.owner_user_id=p.owner_user_id JOIN agent_session s ON s.tenant_id=p.tenant_id AND s.session_id=p.session_id AND s.owner_user_id=p.owner_user_id WHERE p.tenant_id=? AND p.owner_user_id=? AND p.projection_id=? AND p.status='READY' AND p.expires_at>UTC_TIMESTAMP(6) AND s.status='OPEN' AND s.expires_at>UTC_TIMESTAMP(6) AND a.consent_epoch=p.consent_epoch AND a.erase_epoch=p.erase_epoch",owner.tenantId(),owner.userId(),id);
            if(projections.isEmpty())throw MemoryLedger.invalidated();
            var members=db.queryForList("SELECT d.run_id,r.access_scope_json FROM agent_memory_projection_member d JOIN agent_message m ON m.tenant_id=d.tenant_id AND m.owner_user_id=d.owner_user_id AND m.message_id=d.message_id AND m.run_id=d.run_id AND m.content_sha256=d.source_hash JOIN agent_run r ON r.tenant_id=m.tenant_id AND r.owner_user_id=m.owner_user_id AND r.run_id=m.run_id WHERE d.tenant_id=? AND d.owner_user_id=? AND d.projection_id=? AND m.expires_at>UTC_TIMESTAMP(6) AND r.history_visible=1 AND r.data_expires_at>UTC_TIMESTAMP(6)",owner.tenantId(),owner.userId(),id);
            if(members.size()!=((Number)projections.get(0).get("message_count")).intValue())throw MemoryLedger.invalidated();
            for(var member:members)try{
                UUID source=UUID.fromString(member.get("run_id").toString());
                if(runs.add(source)){
                    if(runs.size()>256 || !owner.covers(json.readValue(member.get("access_scope_json").toString(),AccessScope.class)) || !MemoryLedger.memoryOnlyAllowed(db,json,owner,source))throw MemoryLedger.invalidated();
                    queue.addAll(refs(db,json,owner,source));
                }
            }catch(java.io.IOException e){throw MemoryLedger.invalidated();}
        }
        return Set.copyOf(runs);
    }
    public static boolean allowed(JdbcTemplate db,ObjectMapper json,AccessScope owner,UUID run){
        try{sources(db,json,owner,refs(db,json,owner,run));return true;}catch(AgentException e){if(e.code().equals("MEMORY_CONTEXT_INVALIDATED"))return false;throw e;}
    }
    public static void eraseSession(JdbcTemplate db,String tenant,String session){
        // Session lock is already held; account serialization also fences final commits and late publishers.
        for(String owner:db.queryForList("SELECT DISTINCT owner_user_id FROM agent_memory_projection WHERE tenant_id=? AND session_id=? AND status='READY'",String.class,tenant,session)){
            db.queryForList("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",tenant,owner);
            db.update("UPDATE agent_memory_projection SET status='INVALID',content_json=JSON_OBJECT() WHERE tenant_id=? AND owner_user_id=? AND session_id=?",tenant,owner,session);
        }
    }
    public static void eraseOwner(JdbcTemplate db,AccessScope owner){db.update("UPDATE agent_memory_projection SET status='INVALID',content_json=JSON_OBJECT() WHERE tenant_id=? AND owner_user_id=? AND status='READY'",owner.tenantId(),owner.userId());}
}
