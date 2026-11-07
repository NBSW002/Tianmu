package com.tianmu.agent.memory;

import com.tianmu.agent.runtime.control.CallControl;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Thin structured extraction port; transport, budgets and tracing reuse the existing model client. */
public interface MemoryModelPort {
    String version();
    JsonNode evaluate(String instruction,Map<String,?> data,int maxTokens,CallControl control);
    default JsonNode summarize(String instruction,Map<String,?> data,int maxTokens,CallControl control){return evaluate(instruction,data,maxTokens,control);}
}
