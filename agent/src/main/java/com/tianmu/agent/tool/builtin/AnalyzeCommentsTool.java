package com.tianmu.agent.tool.builtin;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
@Component
public final class AnalyzeCommentsTool implements AgentTool {
    public String suspensionGroup(){return "feedback-sources";}
    public String name(){return "analyze_comments";}
    public boolean batchable(){return true;}
    public ToolResult execute(ToolCall call,ToolContext context){return context.prepareFeedback(Source.COMMENT,null);}
}
