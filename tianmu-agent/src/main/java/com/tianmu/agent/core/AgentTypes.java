package com.tianmu.agent.core;

import java.time.*;
import java.util.*;
import com.tianmu.agent.core.TaskTypes.PreparedTask;
import com.tianmu.agent.core.PreparationTypes.*;

/** Shared contracts. Business adapters and model adapters depend on these types. */
public final class AgentTypes {
    private AgentTypes() {}
    public enum Status { QUEUED, RUNNING, WAITING_OPERATION, SUCCEEDED, NEEDS_INPUT, FAILED, CANCELLED }
    public enum Kind { TOOL, ANSWER, CLARIFY }
    public record AccessScope(String tenantId, String userId, Set<Long> videoIds, String authorizationRef) {
        public AccessScope(String tenantId, String userId, Set<Long> videoIds) { this(tenantId, userId, videoIds, ""); }
        public AccessScope { videoIds = Set.copyOf(videoIds); authorizationRef = authorizationRef == null ? "" : authorizationRef; }
    }
    public record ToolCall(String name, Map<String, Object> arguments, String providerCallId,String providerGroupId) {
        public ToolCall(String name,Map<String,Object> arguments) { this(name,arguments,null,null); }
        public ToolCall(String name,Map<String,Object> arguments,String id) { this(name,arguments,id,null); }
        public ToolCall { arguments = ImmutablePayload.map(arguments); }
    }
    public record AnswerNote(String label, String text, Instant updatedAt, String timezone) {}
    public record Answer(String text, List<String> evidenceIds, List<AnswerNote> dataNotes) {
        public Answer(String text, List<String> evidenceIds) { this(text, evidenceIds, List.of()); }
        public Answer {
            evidenceIds = List.copyOf(evidenceIds);
            dataNotes = dataNotes == null ? List.of() : List.copyOf(dataNotes);
        }
    }
    public record Decision(Kind kind, ToolCall call, Answer answer, String clarification,List<ToolCall> additionalCalls) {
        public Decision(Kind kind,ToolCall call,Answer answer,String clarification) { this(kind,call,answer,clarification,List.of()); }
        public Decision { additionalCalls=List.copyOf(additionalCalls); }
        public static Decision tool(String name, Map<String, Object> arguments) {
            return new Decision(Kind.TOOL, new ToolCall(name, arguments), null, null);
        }
        public static Decision answer(String text, List<String> evidenceIds) {
            return new Decision(Kind.ANSWER, null, new Answer(text, evidenceIds), null);
        }
        public static Decision clarify(String text) {
            return new Decision(Kind.CLARIFY, null, null, text);
        }
    }
    public record ModelContext(String message, LocalDate businessDate, AccessScope scope,
                               Set<String> activeSkills, List<ToolResult> observations,
                               List<ToolDefinition> availableTools, int remainingSteps,
                               boolean finalizing, String systemDirective, PreparedRequest preparation, PreparedTask task, MemoryTypes.View memory, List<ToolExchange> exchanges, ReportTaskState reportState) {
        public ModelContext(String message,LocalDate date,AccessScope scope,Set<String> skills,List<ToolResult> results,
                            List<ToolDefinition> tools,int remaining,boolean finalizing,String directive,PreparedRequest preparation,PreparedTask task,MemoryTypes.View memory,List<ToolExchange> exchanges) {
            this(message,date,scope,skills,results,tools,remaining,finalizing,directive,preparation,task,memory,exchanges,null);
        }
        public ModelContext(String message,LocalDate date,AccessScope scope,Set<String> skills,List<ToolResult> results,
                            List<ToolDefinition> tools,int remaining,boolean finalizing,String directive,PreparedRequest preparation,PreparedTask task,MemoryTypes.View memory) {
            this(message,date,scope,skills,results,tools,remaining,finalizing,directive,preparation,task,memory,List.of());
        }
        public ModelContext(String message, LocalDate date, AccessScope scope, Set<String> skills,
                            List<ToolResult> results, List<ToolDefinition> tools, int remaining, boolean finalizing, String directive, PreparedRequest preparation, PreparedTask task) {
            this(message, date, scope, skills, results, tools, remaining, finalizing, directive, preparation, task, MemoryTypes.View.empty());
        }
        public ModelContext(String message, LocalDate date, AccessScope scope, Set<String> skills,
                            List<ToolResult> results, List<ToolDefinition> tools, int remaining, boolean finalizing, String directive, PreparedRequest preparation) {
            this(message, date, scope, skills, results, tools, remaining, finalizing, directive, preparation, null);
        }
        public ModelContext(String message, LocalDate date, AccessScope scope, Set<String> skills,
                            List<ToolResult> results, List<ToolDefinition> tools, int remaining, boolean finalizing, String directive) {
            this(message, date, scope, skills, results, tools, remaining, finalizing, directive, null);
        }
        public ModelContext(String message, LocalDate date, AccessScope scope, Set<String> skills,
                            List<ToolResult> results, List<ToolDefinition> tools, int remaining) {
            this(message, date, scope, skills, results, tools, remaining, false, "");
        }
        public ModelContext {
            activeSkills = Set.copyOf(activeSkills);
            observations = List.copyOf(observations);
            availableTools = List.copyOf(availableTools);
            exchanges = List.copyOf(exchanges);
        }
    }
    public record ToolExchange(ToolCall call,ToolResult result) {}
    public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        public ToolDefinition { inputSchema = ImmutablePayload.map(inputSchema); }
    }
    public record ToolResult(String tool, Map<String, Object> data, String evidenceId, Answer terminalAnswer, PendingOperation pending, String terminalClarification) {
        public ToolResult(String tool,Map<String,Object> data,String evidenceId,Answer answer,PendingOperation pending){this(tool,data,evidenceId,answer,pending,null);}
        public ToolResult(String tool,Map<String,Object> data,String evidenceId,Answer answer){this(tool,data,evidenceId,answer,null);}
        public ToolResult(String tool, Map<String, Object> data, String evidenceId) { this(tool, data, evidenceId, null); }
        public ToolResult {
            data = ImmutablePayload.map(data);
            if(pending!=null && (terminalAnswer!=null || evidenceId!=null || terminalClarification!=null))throw new IllegalArgumentException("Pending operation cannot claim evidence or delivery");
            if(terminalClarification!=null && (terminalClarification.isBlank() || terminalClarification.length()>2000 || terminalAnswer!=null || evidenceId!=null))throw new IllegalArgumentException("Invalid terminal clarification");
        }
        public ToolResult withData(Map<String, Object> value) { return new ToolResult(tool, value, evidenceId, terminalAnswer, pending, terminalClarification); }
    }
    public record Skill(String name, String version, String description,
                        String instructions, Set<String> tools) {
        public Skill { tools = Set.copyOf(tools); }
    }
    public record MetricQuery(long videoId, LocalDate date, String metric) {}
    public record MetricData(long videoId, LocalDate date, String metric, long value,
                             String metricVersion, String source, Instant dataAsOf,
                             String timezone, boolean complete, boolean demo) {}
    public record Evidence(String id, UUID runId, AccessScope owner, MetricData data, DanmakuTypes.Dataset analysis,
                           com.tianmu.agent.core.VideoContent.Facts videoContent, VideoComments.Dataset comments,ReportCheckpoint reportCheckpoint, FeedbackAnalysis.Binding feedback,LookupFacts lookup) {
        public Evidence(String id,UUID runId,AccessScope owner,MetricData data,DanmakuTypes.Dataset analysis,VideoContent.Facts video,VideoComments.Dataset comments,ReportCheckpoint checkpoint,FeedbackAnalysis.Binding feedback){this(id,runId,owner,data,analysis,video,comments,checkpoint,feedback,null);}
        public Evidence(String id,UUID runId,AccessScope owner,MetricData data,DanmakuTypes.Dataset analysis,VideoContent.Facts video,VideoComments.Dataset comments,ReportCheckpoint checkpoint){this(id,runId,owner,data,analysis,video,comments,checkpoint,null);}
        public Evidence(String id,UUID runId,AccessScope owner,MetricData data,DanmakuTypes.Dataset analysis,VideoContent.Facts video,VideoComments.Dataset comments){this(id,runId,owner,data,analysis,video,comments,null);}
        public Evidence(String id, UUID runId, AccessScope owner, MetricData data, DanmakuTypes.Dataset analysis, VideoContent.Facts video) {
            this(id, runId, owner, data, analysis, video, null);
        }
        public Evidence(String id, UUID runId, AccessScope owner, MetricData data) { this(id, runId, owner, data, null, null); }
        public Evidence(String id, UUID runId, AccessScope owner, MetricData data, DanmakuTypes.Dataset analysis) { this(id, runId, owner, data, analysis, null); }
        public Evidence {
            if ((data == null ? 0 : 1) + (analysis == null ? 0 : 1) + (videoContent == null ? 0 : 1) + (comments == null ? 0 : 1) + (reportCheckpoint==null?0:1) + (feedback==null?0:1) + (lookup==null?0:1) != 1)
                throw new IllegalArgumentException("Evidence must contain exactly one fact type");
        }
        public Object facts() { return lookup!=null?lookup:feedback!=null?feedback:reportCheckpoint!=null?reportCheckpoint:comments != null ? comments : videoContent != null ? videoContent : analysis == null ? data : analysis; }
    }
    public record Event(long sequence, String type, Object data, Instant at) {}
    public record Snapshot(UUID runId, Status status, Instant createdAt, int steps,
                           Answer answer, String clarification, String errorCode, String traceId,
                           StopReason stopReason, int modelCalls, Route route, String faqId, String faqVersion, PreparedRequest preparation, PreparedTask task,
                           int preparationCalls, int totalModelCalls, UUID sessionId, long turnSeq, PendingOperation operation, int backgroundModelCalls) {
        public Snapshot { if(backgroundModelCalls<0)throw new IllegalArgumentException("Invalid background call count"); }
        public Snapshot(UUID runId,Status status,Instant createdAt,int steps,Answer answer,String clarification,String errorCode,String traceId,
                        StopReason stopReason,int modelCalls,Route route,String faqId,String faqVersion,PreparedRequest preparation,PreparedTask task,
                        int preparationCalls,int totalModelCalls,UUID sessionId,long turnSeq,PendingOperation operation){this(runId,status,createdAt,steps,answer,clarification,errorCode,traceId,stopReason,modelCalls,route,faqId,faqVersion,preparation,task,preparationCalls,totalModelCalls,sessionId,turnSeq,operation,0);}
        public Snapshot(UUID runId,Status status,Instant createdAt,int steps,Answer answer,String clarification,String errorCode,String traceId,
                        StopReason stopReason,int modelCalls,Route route,String faqId,String faqVersion,PreparedRequest preparation,PreparedTask task,
                        int preparationCalls,int totalModelCalls,UUID sessionId,long turnSeq){this(runId,status,createdAt,steps,answer,clarification,errorCode,traceId,stopReason,modelCalls,route,faqId,faqVersion,preparation,task,preparationCalls,totalModelCalls,sessionId,turnSeq,null);}
    }
    public static boolean terminal(Status status) {
        return status != Status.QUEUED && status != Status.RUNNING && status != Status.WAITING_OPERATION;
    }
}
