package com.tianmu.agent.access;

import com.tianmu.agent.core.AgentTypes.AccessScope;

/** Replace the demo credential with a verified identity from the team's authentication system. */
public interface IdentityPort {
    AccessScope authenticate(String credential);
    /** Reattach process-local credentials after a caller has reauthenticated and the saved scope was authorized. */
    default void renew(AccessScope saved,AccessScope authenticated){
        if(!saved.tenantId().equals(authenticated.tenantId()) || !saved.userId().equals(authenticated.userId()) || !authenticated.covers(saved))
            throw new com.tianmu.agent.core.AgentException("FORBIDDEN","Cannot renew a different authorization scope");
    }
    default AccessScope forCreate(String credential, String message, java.util.UUID replyToRunId) { return authenticate(credential); }
    default AccessScope forCreate(String credential, String message, java.util.UUID replyToRunId,
                                 com.tianmu.agent.core.RequestContext page, java.util.UUID sessionId) {
        AccessScope scope = forCreate(credential, message, replyToRunId);
        if (page != null && !scope.readableVideoIds().contains(Long.parseLong(page.videoId())))
            throw new com.tianmu.agent.core.AgentException("FORBIDDEN", "Current video outside scope");
        return scope;
    }
    default AccessScope forRun(String credential, java.util.UUID runId) { return authenticate(credential); }
    default AccessScope forHistoricalRun(AccessScope caller,java.util.UUID runId){return caller;}
    default AccessScope forSession(String credential, java.util.UUID sessionId, long before) { return authenticate(credential); }
}
