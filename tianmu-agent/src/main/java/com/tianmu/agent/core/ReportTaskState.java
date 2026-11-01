package com.tianmu.agent.core;

import java.util.List;

/** Disposable host facts for the current report task, never a second progress authority. */
public record ReportTaskState(String taskType, List<Feedback> feedback) {
    public ReportTaskState { feedback = List.copyOf(feedback); }
    public record Feedback(String evidenceId, int classifiedGroups, int totalGroups,
                           boolean classificationMerged, boolean reportStored) {
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public boolean emptyFeedback() { return totalGroups == 0; }
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Stage reportStage() {
            return reportStored ? Stage.STORED : classifiedGroups < totalGroups ? Stage.CLASSIFYING : Stage.AWAITING_REPORT;
        }
    }
    public enum Stage { CLASSIFYING, AWAITING_REPORT, STORED }
}
