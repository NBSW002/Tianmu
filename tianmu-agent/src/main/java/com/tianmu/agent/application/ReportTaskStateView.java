package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.EvidenceContext;
import com.tianmu.agent.runtime.ExecutionState;
import java.util.*;

/** Reads completed background assets and report receipts; job progress has a separate durable authority. */
public final class ReportTaskStateView {
    private final EvidenceContext context;
    private final ExecutionState execution;
    public ReportTaskStateView(EvidenceContext context, ExecutionState execution) {
        this.context = context; this.execution = execution;
    }
    public ReportTaskState build() {
        var task = context.task();
        if (task == null || !Set.of("danmaku.report", "audience.report").contains(task.specification().type())) return null;
        var spec = task.specification();
        if(spec.reading().textSources().isEmpty())return new ReportTaskState(spec.type(),List.of());
        var expected=FeedbackTaskPolicy.request(spec);
        var feedback=context.evidenceStore().snapshot().values().stream()
                .filter(e->e.runId().equals(context.runId()) && e.owner().equals(context.scope()) && e.feedback()!=null && e.feedback().request().equals(expected))
                .sorted(Comparator.comparing(e->e.id()))
                .map(e->new ReportTaskState.Feedback(e.id(),Math.toIntExact(e.feedback().counts().total()),execution.hasReport(e.id())))
                .toList();
        return new ReportTaskState(spec.type(),feedback);
    }
}
