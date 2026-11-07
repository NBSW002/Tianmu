package com.tianmu.agent.query;

import com.tianmu.agent.harness.trace.TraceEnvelope;
import java.util.List;

/** Bounded archive lookup; callers must authorize every referenced run before returning data. */
public interface TraceArchive {
    List<TraceEnvelope> find(String traceId);
}
