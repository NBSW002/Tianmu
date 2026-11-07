package com.tianmu.agent.application;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceEnvelope;
import com.tianmu.agent.query.TraceArchive;
import com.tianmu.agent.runtime.store.RunRepository;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class TraceQueryService {
    private final IdentityPort identity;
    private final RunRepository runs;
    private final TraceArchive archive;
    public TraceQueryService(IdentityPort identity, RunRepository runs, TraceArchive archive) {
        this.identity = identity; this.runs = runs; this.archive = archive;
    }
    public List<TraceEnvelope> find(String traceId, String credential) {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) throw new AgentException("INVALID_REQUEST", "Invalid trace ID");
        var caller = identity.authenticate(credential);
        var spans = archive.find(traceId);
        Set<UUID> ids = new HashSet<>();
        for (var span : spans) {
            if (!traceId.equals(span.traceId())) throw new AgentException("TRACE_UNAVAILABLE", "Invalid archive result");
            var attrs = span.attributes();
            if (attrs.containsKey("tenant.id") && !caller.tenantId().equals(attrs.get("tenant.id"))
                    || attrs.containsKey("actor.id") && !caller.userId().equals(attrs.get("actor.id"))) throw missing();
            Object id = attrs.get("run.id");
            if (id != null) {
                try { ids.add(UUID.fromString(id.toString())); }
                catch (IllegalArgumentException error) { throw missing(); }
            }
        }
        if (ids.isEmpty()) throw missing();
        if (ids.size() > 16 || spans.size() > 2000) throw new AgentException("TRACE_TOO_LARGE", "Trace exceeds viewer limits");
        for (UUID id : ids) {
            // Recheck both ownership and current resource permissions, as for payload retrieval.
            runs.load(id, identity.forHistoricalRun(caller, id));
        }
        return spans;
    }
    private static AgentException missing() { return new AgentException("TRACE_NOT_FOUND", "Trace not found"); }
}
