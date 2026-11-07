package com.tianmu.agent.tool;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.skill.SkillCatalog;

/** One trusted Java handler per tool. Descriptions and input schemas live in resources/tools. */
public interface AgentTool {
    enum Exposure { GENERAL, SKILL }
    enum Role { NORMAL, CLARIFICATION }
    default Role role(){return Role.NORMAL;}
    /** A non-null shared identifier admits one atomic suspension group, never parallel side effects. */
    default String suspensionGroup(){return null;}
    String name();
    default int order() { return 100; }
    /** Business tools are hidden by default. Exposure never grants access to business data. */
    default Exposure exposure() { return Exposure.SKILL; }
    default boolean canActivateSkills() { return false; }
    /** Independent reads or host-fenced source submissions may share one model turn. */
    default boolean batchable() { return false; }
    /** Host promises that the handler uses only the isolated read context. */
    default boolean parallelSafe(){return false;}
    default ToolDefinition describe(ToolDefinition declared, SkillCatalog skills) { return declared; }
    ToolResult execute(ToolCall call, ToolContext context);
}
