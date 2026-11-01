package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;
import java.util.function.Consumer;

/** Stream one stable snapshot; the consumer must not call a model or hold the source transaction. */
public interface FeedbackSnapshotPort {
    record Receipt(java.time.Instant dataAsOf, Counts counts) {}
    Receipt export(Request request, AccessScope owner, CallControl control, Consumer<List<Unit>> consumer);
    void authorize(AccessScope owner, long videoId);
}
