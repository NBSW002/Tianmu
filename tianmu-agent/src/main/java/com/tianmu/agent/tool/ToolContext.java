package com.tianmu.agent.tool;

import com.tianmu.agent.core.VideoContent;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/** Trusted host capabilities, without mutable RunState, terminal control, SSE, registry or Harness access. */
public interface ToolContext {
    UUID runId();
    AccessScope scope();
    LocalDate businessDate();
    void checkpoint();
    default Map<String,Object> history(long before,int limit,String query) { throw new com.tianmu.agent.core.AgentException("HISTORY_UNAVAILABLE","History unavailable"); }
    default void checkCreatorDraft(long videoId, String kind) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Creator draft unavailable"); }
    List<Map<String, String>> listSkills();
    Skill activateSkill(String name);
    default List<Map<String, Object>> skillResources(String name) { return List.of(); }
    default Map<String, Object> skillResource(String name, String resource, int offset, int limit) {
        throw new com.tianmu.agent.core.AgentException("SKILL_RESOURCE_NOT_FOUND", "Skill references are unavailable");
    }
    List<ToolDefinition> definitionsForSkill(String name);
    Evidence evidence(String id);
    default Evidence registerLookup(com.tianmu.agent.core.LookupFacts facts){throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED","Resource discovery unavailable");}
    default Map<String,Object> searchFeedback(Evidence evidence,String query,int offset,int limit){throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED","Feedback search unavailable");}
    default ToolResult readReport(UUID sourceRun,String artifact,String section,int offset,int limit){throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED","Historical reports unavailable");}
    default Map<String,Object> readReportOriginal(Evidence evidence,String topic,String original,int offset,int limit){throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED","Historical originals unavailable");}
    default ToolResult analyzeFeedback(String videoEvidenceId,String analysisId){throw new com.tianmu.agent.core.AgentException("FEEDBACK_SERVICE_UNAVAILABLE","Background analysis is unavailable");}
    default Map<String,Object> readFeedback(Evidence evidence,String section,int offset,int limit){throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED","Feedback result unavailable");}
    default Map<String,Object> readFeedback(Evidence evidence,String section,String topic,int offset,int limit){return readFeedback(evidence,section,offset,limit);}
    default Map<String,Object> readFeedbackOriginal(Evidence evidence,String topic,String original,int offset,int limit){throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED","Feedback original unavailable");}
    default void checkFeedback(com.tianmu.agent.core.FeedbackAnalysis.Request request){throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED","Feedback analysis is not available");}
    default void checkFeedbackTopic(Evidence evidence,String topic){throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED","Feedback topic is not available");}
    default com.tianmu.agent.core.AnalysisContract analysisContract(){return null;}
    Evidence registerEvidence(MetricData data);
    default void checkDanmakuQuery(com.tianmu.agent.core.DanmakuTypes.Query query) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Danmaku analysis unavailable"); }
    default Evidence registerDanmakuEvidence(com.tianmu.agent.core.DanmakuTypes.Dataset data) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Danmaku evidence unavailable"); }
    default com.tianmu.agent.core.DanmakuTypes.ReportRef saveReport(String evidenceId, byte[] html) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Report storage unavailable"); }
    default void checkVideoQuery(long videoId) {
        checkpoint();
        if (!scope().videoIds().contains(videoId)) throw new com.tianmu.agent.core.AgentException("FORBIDDEN", "Video outside scope");
    }
    default void checkVideoQuery(long videoId, Long startMs, Long endMs) { checkVideoQuery(videoId); }
    default Evidence registerVideoEvidence(com.tianmu.agent.core.VideoContent.Facts facts) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Video evidence unavailable"); }
    default <T> T videoQuery(Function<CallControl, T> operation) { return query(operation); }
    void checkQuery(MetricQuery query);
    <T> T query(Function<CallControl, T> operation);
}
