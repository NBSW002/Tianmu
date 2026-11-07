package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.interaction.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;

/** Compatibility entry for saved tasks; new skills select a kind-specific schema. */
@Component
public final class PrepareCreatorInteractionsTool implements AgentTool {
    private final CreatorDraftPreparation service;
    public PrepareCreatorInteractionsTool(CreatorDraftStore drafts){service=new CreatorDraftPreparation(drafts);}
    public String name(){return "prepare_creator_interactions";}
    public ToolResult execute(ToolCall call,ToolContext context){return service.prepare(name(),ToolArguments.text(call,"kind"),call,context);}
}
