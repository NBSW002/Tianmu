package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import org.springframework.context.annotation.*;

/** Descriptors remain registered in disabled deployments; dispatcher admission controls exposure. */
@Configuration
public class MemoryTools {
    private AgentTool tool(String name){return new AgentTool(){public String name(){return name;}public Exposure exposure(){return Exposure.GENERAL;}public boolean batchable(){return name.equals("search_memory") || name.equals("read_memory");}public ToolResult execute(ToolCall call,ToolContext context){return context.memory(call);}};}
    @Bean public AgentTool searchMemory(){return tool("search_memory");}
    @Bean public AgentTool readMemory(){return tool("read_memory");}
    @Bean public AgentTool rememberMemory(){return tool("remember_memory");}
    @Bean public AgentTool forgetMemory(){return tool("forget_memory");}
}
