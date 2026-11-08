package com.tianmu.agent.tool.builtin;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.core.AgentTypes.*;
@org.springframework.stereotype.Component
public final class PrepareFeedbackMaterialsTool implements AgentTool {
    public String name(){return "prepare_feedback_materials";}
    public boolean batchable(){return true;}
    public boolean parallelSubmission(){return true;}
    public ToolResult execute(ToolCall call,ToolContext context){return context.prepareFeedbackMaterials();}
}
