package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.PreparationModelPort;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.CallControl;
import com.fasterxml.jackson.databind.*;
import java.util.Map;
import static com.tianmu.agent.model.deepseek.StructuredModelOutput.*;

final class DeepSeekPreparationModel implements PreparationModelPort {
    private final DeepSeekClient client;
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final String emotionPrompt, understandingPrompt, intentPrompt;
    DeepSeekPreparationModel(DeepSeekClient client, DeepSeekProperties config, ObjectMapper mapper, PromptCatalog prompts) {
        this.client = client; this.config = config; this.mapper = mapper;
        String common = "\n\n" + prompts.text("common.current-focus-boundary") + "\n\n" + prompts.text("common.json-output");
        emotionPrompt = prompts.text("preparation.emotion") + common;
        understandingPrompt = prompts.text("preparation.understanding") + common;
        intentPrompt = prompts.text("preparation.intent") + common;
    }
    private JsonNode call(String prompt, Input input, CallControl control, int tokens) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("memory", input.memory()); payload.put("query", input.query()); payload.put("businessDate", input.businessDate().toString());
        payload.put("requestedAt",input.requestedAt().toString());
        if(prompt.equals(intentPrompt) && !input.intentCandidates().isEmpty())payload.put("intentCandidates",input.intentCandidates());
        try {
            payload.put("serverTaskCandidate",com.tianmu.agent.preparation.TaskMeaning.defaults()
                    .resolve(input.query(),input.businessDate(),input.requestedAt(),input.memory()));
        } catch(com.tianmu.agent.core.AgentException ambiguous) {
            if(!"AMBIGUOUS_ANALYSIS_SOURCES".equals(ambiguous.code()))throw ambiguous;
            payload.put("serverTaskCandidate",Map.of("type","unclassified","requiresClarification",true));
        }
        return client.complete(config.preparationModel(), client.messages(prompt, payload,control), tokens, control);
    }
    @Override public String revision() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(
                java.util.List.of("chat-native-v1",config.baseUrl().toString(),config.preparationModel(),config.preparationMaxTokens(),emotionPrompt,understandingPrompt,intentPrompt))));
        } catch(Exception error){throw new IllegalStateException("Cannot fingerprint preparation model",error);}
    }
    public Emotion emotion(Input input, CallControl control) {
        JsonNode result = call(emotionPrompt, input, control, 128);
        fields(result, "emotion"); return Emotion.valueOf(text(result, "emotion"));
    }
    public Intent intent(Input input, CallControl control) {
        return recognizeIntent(input,control).intent();
    }
    @Override public IntentRecognition recognizeIntent(Input input,CallControl control) {
        // Explicit legacy adapter inputs retain their old response contract.
        if(input.intentCandidates().isEmpty()) {
            JsonNode result=call(intentPrompt+"\n本请求未提供候选清单，兼容输出仅包含 intent 字段，值为 VIDEO_METRIC、DANMAKU_REPORT、KNOWLEDGE、OTHER、UNKNOWN。",input,control,128);
            fields(result,"intent");return new IntentRecognition(Intent.valueOf(text(result,"intent")),null,java.util.List.of(),java.util.List.of());
        }
        JsonNode result=call(intentPrompt,input,control,256);fields(result,"primary","related");
        if(!result.path("related").isArray() || result.path("related").size()>4)throw new IllegalArgumentException("Invalid related goals");
        var related=new java.util.ArrayList<String>();
        for(var item:result.path("related")){if(!item.isTextual())throw new IllegalArgumentException("Invalid related goal");related.add(item.textValue());}
        return com.tianmu.agent.preparation.IntentCandidateCatalog.validate(input,
                new IntentRecognition(Intent.UNKNOWN,text(result,"primary"),related,input.intentCandidates()));
    }
    public Understanding understand(Input input, CallControl control) {
        JsonNode result = call(understandingPrompt, input, control, config.preparationMaxTokens());
        boolean structured=result.has("issue") || result.has("missingFields");
        if(structured)fields(result,"needsClarification","clarificationQuestion","conditions","rewrittenQuery","issue","missingFields");
        else fields(result, "needsClarification", "clarificationQuestion", "conditions", "rewrittenQuery");
        if (!result.path("needsClarification").isBoolean() || !result.path("conditions").isObject())
            throw new IllegalArgumentException("Invalid understanding structure");
        for (String key : new String[]{"clarificationQuestion", "rewrittenQuery"})
            if (!result.path(key).isNull() && !result.path(key).isTextual())
                throw new IllegalArgumentException("Invalid understanding text");
        Map<String, Object> conditions = mapper.convertValue(result.path("conditions"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        boolean clarify = result.path("needsClarification").booleanValue();
        String rewrite = result.path("rewrittenQuery").textValue();
        if(!structured)return new Understanding(clarify,result.path("clarificationQuestion").textValue(),conditions,rewrite);
        if(!result.path("issue").isTextual() || !result.path("missingFields").isArray())throw new IllegalArgumentException("Invalid clarification issue");
        var missing=new java.util.ArrayList<String>();
        for(var field:result.path("missingFields")){if(!field.isTextual())throw new IllegalArgumentException("Invalid missing field");missing.add(field.textValue());}
        return new Understanding(clarify,result.path("clarificationQuestion").textValue(),conditions,rewrite,ClarificationIssue.valueOf(result.path("issue").asText()),missing);
    }
}
