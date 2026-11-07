package com.tianmu.agent.core;

import java.util.List;

/** Completed analysis receipts awaiting report storage; background progress belongs to the durable job. */
public record ReportTaskState(String taskType,List<Feedback> feedback) {
    public ReportTaskState { feedback=List.copyOf(feedback); }
    public record Feedback(String evidenceId,int totalMessages,boolean reportStored) {
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public boolean emptyFeedback(){return totalMessages==0;}
        @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Stage reportStage(){return reportStored?Stage.STORED:Stage.AWAITING_REPORT;}
    }
    public enum Stage { AWAITING_REPORT,STORED }
}
