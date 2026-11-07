package com.tianmu.agent.runtime.spi;

import com.tianmu.agent.core.ReportTaskState;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.TaskTypes.PreparedTask;
import java.util.*;

/** Read-only task-owned execution hints; cannot grant access or mutate the execution ledger. */
public interface TaskExecutionPolicy {
    record Input(PreparedTask task,int modelCallLimit,Map<String,Evidence> evidence,Set<String> registeredArtifacts) {
        public Input { evidence=Map.copyOf(evidence);registeredArtifacts=Set.copyOf(registeredArtifacts); }
    }
    int maxTurns(Input input,int configured);
    ReportTaskState project(Input input);
}
