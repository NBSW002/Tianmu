package com.tianmu.agent.video.index;

import com.tianmu.agent.core.AgentTypes.AccessScope;

public interface VideoIndexStore {
    VideoIndex get(AccessScope scope,long videoId);
    /** Atomic admission: existing active work wins, including across application instances. */
    VideoIndex start(AccessScope scope,VideoIndex proposed);
    /** Compare-and-set against the complete prior value, protecting cancellation and retries. */
    boolean replace(AccessScope scope,VideoIndex expected,VideoIndex updated);
}
