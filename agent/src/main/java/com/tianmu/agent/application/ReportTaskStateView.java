package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.EvidenceContext;
import com.tianmu.agent.runtime.ExecutionState;
import java.util.*;

/** Reads completed background assets and report receipts; job progress has a separate durable authority. */
public final class ReportTaskStateView {
    private final java.util.function.Supplier<com.tianmu.agent.runtime.spi.TaskExecutionPolicy.Input> inputs;
    public ReportTaskStateView(EvidenceContext context, ExecutionState execution) {
        inputs=()->new com.tianmu.agent.runtime.spi.TaskExecutionPolicy.Input(context.task(),0,context.evidenceStore().snapshot(),execution.artifactEvidence());
    }
    public ReportTaskStateView(com.tianmu.agent.runtime.spi.TaskExecutionPolicy.Input input) { inputs=()->input; }
    public ReportTaskState build() {
        var input=inputs.get();var task = input.task();
        if (task == null || !Set.of("danmaku.report", "audience.report").contains(task.specification().type())) return null;
        var spec = task.specification();
        if(spec.reading().textSources().isEmpty())return new ReportTaskState(spec.type(),List.of());
        var expected=FeedbackTaskPolicy.request(spec);
        var feedback=input.evidence().values().stream()
                .filter(e->e.runId().equals(task.runId()) && e.owner().equals(task.scope()) && e.feedback()!=null && (e.feedback().request().equals(expected) || e.feedback().request().equals(FeedbackTaskPolicy.pipelineRequest(spec))))
                .sorted(Comparator.comparing(e->e.id()))
                .map(e->new ReportTaskState.Feedback(e.id(),Math.toIntExact(e.feedback().counts().total()),input.registeredArtifacts().contains(e.id())))
                .toList();
        return new ReportTaskState(spec.type(),feedback);
    }
}
