package com.tianmu.agent.runtime.spi;

import com.tianmu.agent.runtime.RunState;
import java.util.*;

/** Module-owned nonblocking dispatch; actual work uses its own bounded workers and durable ledger. */
public interface OperationHandler {
    Set<String> kinds();
    void resume(List<RunState> waiting);
}
