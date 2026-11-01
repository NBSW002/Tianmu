package com.tianmu.agent.core;

import java.util.List;

/** Disposable host facts for the current report task, never a second progress authority. */
public record ReportTaskState(String taskType, List<Feedback> feedback) {
    public ReportTaskState { feedback = List.copyOf(feedback); }
    public record Feedback(String evidenceId, int classifiedGroups, int totalGroups,
                           boolean classificationMerged, boolean reportStored) {}
}
