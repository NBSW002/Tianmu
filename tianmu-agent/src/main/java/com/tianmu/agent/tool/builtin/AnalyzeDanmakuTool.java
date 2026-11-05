package com.tianmu.agent.tool.builtin;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
@Component
public final class AnalyzeDanmakuTool implements AgentTool {
    public String name(){return "analyze_danmaku";}
    public boolean batchable(){return true;}
    public ToolResult execute(ToolCall call,ToolContext context){return context.prepareFeedback(Source.DANMAKU,call.arguments().containsKey("video_evidence_id")?ToolArguments.text(call,"video_evidence_id"):null);}
}
