package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Requests a host-controlled NEEDS_INPUT outcome; cannot mutate scope or execute business operations. */
@Component
public final class AskClarificationTool implements AgentTool {
    public Role role(){return Role.CLARIFICATION;}
    public String name(){return "ask_clarification";}
    public Exposure exposure(){return Exposure.GENERAL;}
    public ToolResult execute(ToolCall call,ToolContext context){
        context.checkpoint();
        return new ToolResult(name(),Map.of(),null,null,null,ToolArguments.text(call,"question"));
    }
}
