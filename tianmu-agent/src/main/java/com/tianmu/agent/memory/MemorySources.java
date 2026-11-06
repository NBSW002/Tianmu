package com.tianmu.agent.memory;

/** Shared predicate for source alias s. Natural retention never silently extends derived facts. */
public final class MemorySources {
    private MemorySources(){}
    public static final String READABLE="s.status='ACTIVE' AND (s.source_kind IN ('MANUAL','EXPLICIT_DECLARATION') OR (s.source_expires_at>UTC_TIMESTAMP(6) AND EXISTS (SELECT 1 FROM agent_run ar JOIN agent_session ss ON ss.tenant_id=ar.tenant_id AND ss.session_id=ar.session_id WHERE ar.tenant_id=s.tenant_id AND ar.owner_user_id=s.owner_user_id AND ar.run_id=s.origin_run_id AND ar.data_expires_at>UTC_TIMESTAMP(6) AND ss.status='OPEN' AND ss.expires_at>UTC_TIMESTAMP(6))))";
}
