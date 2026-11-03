package com.tianmu.agent.tool.builtin;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component
public final class ReadReportTool implements AgentTool {
    public String name(){return "read_report";}
    public ToolResult execute(ToolCall call,ToolContext context){
        final UUID source;
        try{source=UUID.fromString(ToolArguments.text(call,"run_id"));}
        catch(IllegalArgumentException invalid){throw new com.tianmu.agent.core.AgentException("INVALID_ARGUMENTS","run_id must be a historical run UUID");}
        return context.readReport(source,ToolArguments.text(call,"artifact_id"),
                call.arguments().containsKey("section")?ToolArguments.text(call,"section"):"summary",
                call.arguments().containsKey("offset")?Math.toIntExact(ToolArguments.integer(call,"offset")):0,
                call.arguments().containsKey("limit")?Math.toIntExact(ToolArguments.integer(call,"limit")):10);
    }
}
