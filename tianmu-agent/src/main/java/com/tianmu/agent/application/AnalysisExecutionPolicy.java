package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.TaskTypes.Specification;
import java.util.*;
import static com.tianmu.agent.core.AnalysisContract.Source.*;

/** Application policy shared by discovery, execution admission and delivery. */
public final class AnalysisExecutionPolicy {
    private AnalysisExecutionPolicy() {}
    public static boolean permits(String tool, Specification spec) {
        if(spec!=null && spec.type().equals("resource.lookup"))return Set.of("list_skills","read_skill","read_history","read_result","ask_clarification","search_my_videos","read_report","search_feedback").contains(tool);
        if (spec == null || spec.analysis() == null) return true;
        var contract = spec.analysis();
        return switch (tool) {
            case "analyze_comments" -> contract.includes(COMMENT) && spec.typed() instanceof TaskConditions.Report && contract.reportRequired();
            case "analyze_danmaku" -> contract.includes(DANMAKU) && spec.typed() instanceof TaskConditions.Report && contract.reportRequired();
            case "analyze_feedback" -> !contract.textSources().isEmpty() && (spec.typed() instanceof TaskConditions.Report && Set.of("audience.report","danmaku.report","comments.analysis").contains(spec.type()) || spec.typed() instanceof TaskConditions.Interaction draft && draft.feedback()!=null);
            case "create_feedback_report" -> contract.reportRequired() && !contract.textSources().isEmpty();
            case "prepare_comment_draft" -> spec.typed() instanceof TaskConditions.Interaction draft && draft.kind().equals("COMMENT");
            case "prepare_danmaku_draft" -> spec.typed() instanceof TaskConditions.Interaction draft && draft.kind().equals("DANMAKU");
            case "analyze_video_content" -> contract.includes(VIDEO);
            case "query_danmaku" -> contract.includes(DANMAKU) && "danmaku.overview".equals(spec.type());
            case "query_video_metrics" -> contract.includes(DANMAKU) && "video.metric".equals(spec.type());
            case "create_video_report" -> contract.reportRequired() && contract.sources().equals(Set.of(VIDEO));
            default -> true;
        };
    }
    public static void requireSource(Specification spec, AnalysisContract.Source source) {
        if (spec != null && spec.analysis() != null && !spec.analysis().includes(source))
            throw new AgentException("TASK_CONSTRAINT_VIOLATION", "This source is outside the frozen analysis request");
    }
    public static int maxTurns(Specification spec, int configured, int modelCalls) {
        return spec != null && Set.of("danmaku.report", "audience.report", "comments.analysis").contains(spec.type())
                ? Math.max(configured, Math.min(32, modelCalls - 1)) : configured;
    }
    public static void requireComplete(Specification spec, List<Evidence> evidence) {
        if (spec.analysis() == null) { EvidencePresentation.requireKind(spec, evidence); return; }
        var required = spec.analysis();
        var covered = EnumSet.noneOf(AnalysisContract.Source.class);
        for (Evidence item : evidence) {
            if(item.feedback()!=null){
                FeedbackTaskPolicy.require(spec,item.feedback().request());
                item.feedback().request().sources().forEach(s->covered.add(AnalysisContract.Source.valueOf(s.name())));
                if(item.feedback().request().grounding()==FeedbackAnalysis.Grounding.VIDEO_GROUNDED)covered.add(VIDEO);
            }
            if (item.videoContent() != null) covered.add(VIDEO);
            if (item.comments() != null) covered.add(COMMENT);
            if (item.data() != null) covered.add(DANMAKU);
            if (item.analysis() != null) {
                if (item.analysis().sources() == null) covered.add(DANMAKU);
                else covered.addAll(item.analysis().sources().selectedSources());
            }
        }
        if (!required.sources().containsAll(covered)) throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Answer cites an unselected source");
        if(permits("analyze_feedback",spec) && spec.analysis()!=null && evidence.stream().noneMatch(e->e.feedback()!=null))
            throw new AgentException("INVALID_EVIDENCE","Feedback analysis requires a completed background analysis asset");
        if (!covered.containsAll(required.sources())) throw new AgentException("INVALID_EVIDENCE", "Answer lacks a selected analysis source");
    }
    /** Replay host-recorded exchanges so continuation recovery preserves read coverage without a second progress store. */
    public static void requireTopicsRead(Specification spec, List<Evidence> evidence, List<ToolExchange> exchanges) {
        if (spec.analysis() == null || spec.analysis().reportRequired()
                || !(spec.typed() instanceof TaskConditions.Report) || !permits("analyze_feedback", spec)) return;
        for (Evidence item : evidence) {
            if (item.feedback() == null) continue;
            // A completed, verified empty snapshot has no semantic topics to read. Non-empty
            // snapshots still require the same contiguous, host-recorded reading coverage.
            if (item.feedback().counts().total() == 0) continue;
            int next = 0;
            boolean complete = false;
            for (ToolExchange exchange : exchanges) {
                var call = exchange.call(); var result = exchange.result();
                if (!"read_result".equals(call.name()) || !"read_result".equals(result.tool())
                        || !item.id().equals(call.arguments().get("result_id")) || !item.id().equals(result.evidenceId())
                        || !"topics".equals(call.arguments().get("section"))) continue;
                Object offset = call.arguments().getOrDefault("offset", 0);
                if (!(offset instanceof Number start) || start.longValue() != next
                        || !(result.data().get("topics") instanceof List<?> topics)
                        || !(result.data().get("next_offset") instanceof Number end)
                        || end.longValue() != (long) next + topics.size()) continue;
                next = end.intValue();
                if (Boolean.TRUE.equals(result.data().get("complete"))) { complete = true; break; }
            }
            if (!complete) throw new AgentException("INVALID_EVIDENCE", "Read all top-level feedback topics with read_result section=topics, starting at offset 0 and following next_offset, before answering");
        }
    }
}
