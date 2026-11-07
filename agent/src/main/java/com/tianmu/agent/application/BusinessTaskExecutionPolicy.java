package com.tianmu.agent.application;

import com.tianmu.agent.core.ReportTaskState;
import com.tianmu.agent.runtime.spi.TaskExecutionPolicy;
import org.springframework.stereotype.Component;

@Component
public final class BusinessTaskExecutionPolicy implements TaskExecutionPolicy {
    public int maxTurns(Input input,int configured) {
        return AnalysisExecutionPolicy.maxTurns(input.task()==null?null:input.task().specification(),configured,input.modelCallLimit());
    }
    public ReportTaskState project(Input input) { return new ReportTaskStateView(input).build(); }
}
