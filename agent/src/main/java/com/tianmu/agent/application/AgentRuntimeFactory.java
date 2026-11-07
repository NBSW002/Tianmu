package com.tianmu.agent.application;

import com.tianmu.agent.runtime.AgentRuntime;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.tool.ToolDispatcher;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.prompt.PromptCatalog;

/** Explicit standalone composition, shared by embedded/demo fixtures; production uses Spring constructor injection. */
public final class AgentRuntimeFactory {
    private AgentRuntimeFactory() {}
    public static AgentRuntime create(ModelPort model,ToolDispatcher tools,RuntimeHarness harness) {
        return create(model,tools,harness,new PromptCatalog(),new DeliveryService(harness));
    }
    public static AgentRuntime create(ModelPort model,ToolDispatcher tools,RuntimeHarness harness,PromptCatalog prompts) {
        return create(model,tools,harness,prompts,new DeliveryService(harness));
    }
    public static AgentRuntime create(ModelPort model,ToolDispatcher tools,RuntimeHarness harness,PromptCatalog prompts,DeliveryService delivery) {
        return new AgentRuntime(model,tools,harness,prompts,delivery,new BusinessTaskExecutionPolicy(),new BusinessCompletionEvaluator());
    }
}
