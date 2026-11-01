package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.EvidenceContext;
import com.tianmu.agent.runtime.ExecutionState;
import java.util.*;

/** Reads existing admitted checkpoints and artifact receipts; does not infer progress from model text. */
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
        var query = ((TaskConditions.Report)spec.typed()).query();
        var progress = new ReportProgressService(context, execution);
        var feedback = context.evidenceStore().snapshot().values().stream()
                .filter(e -> e.runId().equals(context.runId()) && e.owner().equals(context.scope())
                        && e.analysis() != null && e.analysis().audience() != null && e.analysis().query().equals(query))
                .filter(e -> "audience.report".equals(spec.type()) == (e.analysis().sources() != null))
                .filter(e -> spec.analysis() == null || spec.analysis().textSources().equals(e.analysis().sources() == null
                        ? Set.of(AnalysisContract.Source.DANMAKU) : e.analysis().sources().selectedSources()))
                .sorted(Comparator.comparing(e -> e.id()))
                .map(e -> {
                    var accepted = progress.load(e.id());
                    return new ReportTaskState.Feedback(e.id(), accepted.offset(), e.analysis().audience().corpus().size(),
                            accepted.merged(), execution.hasReport(e.id()));
                }).toList();
        return new ReportTaskState(spec.type(), feedback);
    }
}
