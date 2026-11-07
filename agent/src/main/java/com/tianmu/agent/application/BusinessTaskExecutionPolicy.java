package com.tianmu.agent.application;

import com.tianmu.agent.core.ReportTaskState;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.spi.TaskExecutionPolicy;
import org.springframework.stereotype.Component;

@Component
public final class BusinessTaskExecutionPolicy implements TaskExecutionPolicy {
    public int maxTurns(RunState run,int configured) {
        return AnalysisExecutionPolicy.maxTurns(run.task()==null?null:run.task().specification(),configured,run.modelCallLimit());
    }
    public ReportTaskState project(RunState run) { return new ReportTaskStateView(run,run.execution()).build(); }
}
