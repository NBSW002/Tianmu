package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.memory.MemoryModelPort;
import com.tianmu.agent.runtime.control.CallControl;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component @Profile("deepseek")
public final class DeepSeekMemoryModel implements MemoryModelPort {
    private final DeepSeekClient client;
    private final DeepSeekProperties config;
    private final ObjectMapper json;
    private final com.tianmu.agent.model.ContextPolicy policy;
    public DeepSeekMemoryModel(DeepSeekProperties config,ObjectMapper json,com.tianmu.agent.model.ContextPolicy policy,io.opentelemetry.api.trace.Tracer tracer){this.config=config;this.json=json;this.policy=policy;client=new DeepSeekClient(config,json,policy,tracer);}
    public String version(){return "memory-v2:"+java.util.UUID.nameUUIDFromBytes((config.baseUrl()+":"+config.preparationModel()+":context-envelope-v1").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public JsonNode evaluate(String instruction,Map<String,?> data,int maxTokens,CallControl control){
        return request(instruction,data,maxTokens,control,com.tianmu.agent.core.ContextEnvelope.Phase.EXTRACTION);
    }
    @Override public JsonNode summarize(String instruction,Map<String,?> data,int maxTokens,CallControl control){
        return request(instruction,data,maxTokens,control,com.tianmu.agent.core.ContextEnvelope.Phase.COMPACTION);
    }
    private JsonNode request(String instruction,Map<String,?> data,int maxTokens,CallControl control,com.tianmu.agent.core.ContextEnvelope.Phase phase){
        try {
            var envelope=new com.tianmu.agent.core.ContextEnvelope("1.0",phase,"memory-v2",java.util.List.of(
                new com.tianmu.agent.core.ContextEnvelope.Part(com.tianmu.agent.core.ContextEnvelope.Slot.SYSTEM_PROMPT,"memory-v2",Map.of("text",instruction)),
                new com.tianmu.agent.core.ContextEnvelope.Part(com.tianmu.agent.core.ContextEnvelope.Slot.CURRENT_CONTEXT,"source-v1",Map.of("messages",java.util.List.of(Map.of("role","user","content",json.writeValueAsString(data)))))));
            return com.tianmu.agent.harness.trace.TracePrompt.call("memory."+phase.name().toLowerCase(java.util.Locale.ROOT),version(),phase.name(),instruction,java.util.List.of(),()->client.complete(config.preparationModel(),new com.tianmu.agent.model.NativeContextProjector(json,policy).project(envelope).messages(),maxTokens,control));
        }catch(java.io.IOException e){throw new IllegalArgumentException("Invalid memory model input",e);}
    }
}
