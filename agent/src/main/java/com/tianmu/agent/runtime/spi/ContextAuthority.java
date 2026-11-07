package com.tianmu.agent.runtime.spi;

import com.tianmu.agent.runtime.RunState;

/** Revalidates source authority before inference; implementations own the backing ledger. */
public interface ContextAuthority {
    void verify(RunState run);
}
