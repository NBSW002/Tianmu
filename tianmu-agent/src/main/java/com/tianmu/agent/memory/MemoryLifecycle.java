package com.tianmu.agent.memory;

import org.springframework.jdbc.core.JdbcTemplate;

/** Runs inside the caller's session-first transaction, before source rows are removed. */
public final class MemoryLifecycle {
    private MemoryLifecycle(){}
    public static void session(JdbcTemplate db,String tenant,String session,boolean explicitDelete) {
        ProjectionChecks.eraseSession(db,tenant,session);
        String filter="tenant_id=? AND origin_session_id=? AND status='ACTIVE'"+(explicitDelete?"":" AND source_kind IN ('AUTO_EXTRACTED','TASK_DERIVED') AND (source_expires_at<=UTC_TIMESTAMP(6) OR EXISTS(SELECT 1 FROM agent_session ss WHERE ss.tenant_id=agent_memory_source.tenant_id AND ss.session_id=agent_memory_source.origin_session_id AND (ss.status='DELETING' OR ss.expires_at<=UTC_TIMESTAMP(6))))");
        for(String owner:db.queryForList("SELECT DISTINCT owner_user_id FROM agent_memory_source WHERE "+filter,String.class,tenant,session)) {
            db.queryForList("SELECT memory_seq FROM agent_memory_account WHERE tenant_id=? AND owner_user_id=? FOR UPDATE",tenant,owner);
            db.update("UPDATE agent_memory_account SET memory_seq=memory_seq+1,erase_epoch=erase_epoch+1 WHERE tenant_id=? AND owner_user_id=?",tenant,owner);
            ProjectionChecks.eraseOwner(db,new com.tianmu.agent.core.AgentTypes.AccessScope(tenant,owner,java.util.Set.of()));
            db.update("UPDATE agent_memory_source SET declaration='',content_hash=REPEAT('0',64),status='DELETED' WHERE "+filter+" AND owner_user_id=?",tenant,session,owner);
            db.update("UPDATE agent_memory_revision r JOIN agent_memory_dependency d ON d.tenant_id=r.tenant_id AND d.owner_user_id=r.owner_user_id AND d.memory_id=r.memory_id AND d.version=r.version JOIN agent_memory_source s ON s.tenant_id=d.tenant_id AND s.owner_user_id=d.owner_user_id AND s.source_id=d.source_id SET r.content='' WHERE r.tenant_id=? AND r.owner_user_id=? AND s.status='DELETED'",tenant,owner);
            db.update("UPDATE agent_memory_item i JOIN agent_memory_revision r ON r.tenant_id=i.tenant_id AND r.owner_user_id=i.owner_user_id AND r.memory_id=i.memory_id AND r.version=i.current_version SET i.status='DELETED' WHERE i.tenant_id=? AND i.owner_user_id=? AND r.content=''",tenant,owner);
            db.update("DELETE k FROM agent_memory_keyword k JOIN agent_memory_revision r ON r.tenant_id=k.tenant_id AND r.owner_user_id=k.owner_user_id AND r.memory_id=k.memory_id AND r.version=k.version WHERE k.tenant_id=? AND k.owner_user_id=? AND r.content=''",tenant,owner);
        }
    }
}
