package com.tianmu.agent.runtime.spi;

import com.tianmu.agent.core.ReportTaskState;
import com.tianmu.agent.runtime.RunState;

/** Read-only task-owned execution hints; cannot grant access or mutate the execution ledger. */
public interface TaskExecutionPolicy {
    int maxTurns(RunState run,int configured);
    ReportTaskState project(RunState run);
}
