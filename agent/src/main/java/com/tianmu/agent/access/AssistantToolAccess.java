package com.tianmu.agent.access;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import java.util.Set;

public final class AssistantToolAccess {
    private AssistantToolAccess() {}
    private static final Set<String> CREATOR = Set.of("prepare_feedback_materials","generate_feedback_report","analyze_comments", "analyze_danmaku", "analyze_feedback",
        "query_audience", "query_danmaku", "query_video_comments", "query_video_metrics",
        "create_danmaku_report", "create_feedback_report", "save_danmaku_classification",
        "prepare_comment_draft", "prepare_danmaku_draft", "prepare_creator_interactions");
    public static boolean permits(String tool, AccessScope scope) {
        return !CREATOR.contains(tool) || !scope.videoIds().isEmpty();
    }
}
