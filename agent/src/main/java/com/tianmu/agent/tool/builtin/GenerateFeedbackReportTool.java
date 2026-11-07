package com.tianmu.agent.tool.builtin;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.core.AgentTypes.*;
@org.springframework.stereotype.Component
public final class GenerateFeedbackReportTool implements AgentTool {
    public String name(){return "generate_feedback_report";}
    public ToolResult execute(ToolCall call,ToolContext context){return context.generateFeedbackReport(ToolArguments.text(call,"materials_id"),call.arguments().containsKey("video_evidence_id")?ToolArguments.text(call,"video_evidence_id"):null);}
}
