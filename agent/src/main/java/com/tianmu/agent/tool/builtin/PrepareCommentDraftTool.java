package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.interaction.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;

@Component
public final class PrepareCommentDraftTool implements AgentTool {
    private final CreatorDraftPreparation service;
    public PrepareCommentDraftTool(CreatorDraftStore drafts){service=new CreatorDraftPreparation(drafts);}
    public String name(){return "prepare_comment_draft";}
    public ToolCall normalize(ToolCall call){return CreatorDraftPreparation.normalize(call);}
    public ToolArguments.Validation preflight(ToolCall call,ToolContext context){return CreatorDraftPreparation.preflight(call,context);}
    public ToolResult execute(ToolCall call,ToolContext context){return service.prepare(name(),"COMMENT",call,context);}
}
