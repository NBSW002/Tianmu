package com.tianmu.agent.runtime.spi;

import com.tianmu.agent.runtime.RunState;

/** Reauthorizes derived private trace payloads against their original source ledger. */
public interface TraceSourceAuthority {
    void verify(RunState run, com.tianmu.agent.core.AgentTypes.AccessScope caller);
}
