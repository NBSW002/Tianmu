package com.tianmu.agent.tool.builtin;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
@Component
public final class SearchFeedbackTool implements AgentTool {
    public String name(){return "search_feedback";}
    public ToolResult execute(ToolCall call,ToolContext context){
        var evidence=context.evidence(ToolArguments.text(call,"result_id"));
        return new ToolResult(name(),context.searchFeedback(evidence,ToolArguments.text(call,"query"),
                call.arguments().containsKey("offset")?Math.toIntExact(ToolArguments.integer(call,"offset")):0,
                call.arguments().containsKey("limit")?Math.toIntExact(ToolArguments.integer(call,"limit")):10),evidence.id());
    }
}
