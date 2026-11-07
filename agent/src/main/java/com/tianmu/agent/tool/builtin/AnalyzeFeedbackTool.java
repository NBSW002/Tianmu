package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;

/** The host derives scope from PreparedTask; the model supplies only owned evidence references. */
@Component
public final class AnalyzeFeedbackTool implements AgentTool {
    public String name(){return "analyze_feedback";}
    public ToolResult execute(ToolCall call,ToolContext context){
        return context.analyzeFeedback(call.arguments().containsKey("video_evidence_id")?ToolArguments.text(call,"video_evidence_id"):null,
                call.arguments().containsKey("analysis_id")?ToolArguments.text(call,"analysis_id"):null);
    }
}
