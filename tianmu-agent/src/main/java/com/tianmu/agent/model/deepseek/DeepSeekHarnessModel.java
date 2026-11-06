package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import static com.tianmu.agent.model.deepseek.StructuredModelOutput.*;

public final class DeepSeekHarnessModel implements ModelPort {
    private final DeepSeekClient client;
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final String systemPrompt, focusPrompt;
    public DeepSeekHarnessModel(DeepSeekClient client, DeepSeekProperties config, ObjectMapper mapper, PromptCatalog prompts) {
        this.client = client; this.config = config; this.mapper = mapper;
        focusPrompt = prompts.text("common.current-focus-boundary"); systemPrompt = prompts.text("harness.system");
    }
    public boolean streamsText(){return true;}
    public String modelName() { return config.harnessModel(); }
    public Decision decide(ModelContext context) {
        return decide(context, new CallControl(Instant.now().plusSeconds(30), new CancellationToken()));
    }
    public Decision decide(ModelContext context, CallControl control) {
        String prompt = systemPrompt + "\n\n" + focusPrompt;
        String requiredTool=context.finalizing() || context.availableTools().isEmpty()?null:com.tianmu.agent.application.ResourceSelection.requiredTool(context);
        if("read_skill".equals(requiredTool))prompt+="\n本轮先读取 resource-discovery Skill，以开放已有报告读取工具。";
        if("read_report".equals(requiredTool))prompt+="\n已有报告链接已在当前问题或历史工具结果中给出，不要再搜索历史。提取该链接的 run_id 与 artifact_id，读取报告；回答选题与结论时读取 topics。";
        if("ask_clarification".equals(requiredTool))prompt+="\n当前可读历史已检索结束，没有找到可验证的报告引用。请用户提供报告链接，不猜测报告编号。";
        JsonNode result = client.completeHarness(config.harnessModel(), client.harnessMessages(prompt,context),context.availableTools(), config.harnessMaxTokens(), control,requiredTool);
        try {
            if(result.path("tool_calls").isArray()) {
                var calls=result.path("tool_calls");
                if(calls.isEmpty() || calls.size()>4)throw new IllegalArgumentException("Native call batch too large");
                var parsed=new ArrayList<ToolCall>();Set<String> ids=new HashSet<>();String group=calls.size()>1?text(calls.get(0),"id"):null;
                for(var nativeCall:calls) {
                    var call=nativeCall.path("function");String name=text(call,"name"),id=text(nativeCall,"id");
                    if(!"function".equals(text(nativeCall,"type")) || id.length()>160 || !id.matches("[A-Za-z0-9_.:-]+") || !ids.add(id)
                            || context.availableTools().stream().noneMatch(t->t.name().equals(name)))throw new IllegalArgumentException("Unknown native tool");
                    var args=client.toolArguments(text(call,"arguments"));
                    parsed.add(new ToolCall(name,mapper.convertValue(args,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),id,group,result.path("content").asText("")));
                }
                return new Decision(Kind.TOOL,parsed.get(0),null,null,parsed.subList(1,parsed.size()));
            }
            String content=text(result,"content").strip();
            if(content.contains("<｜｜DSML｜｜"))throw new IllegalArgumentException("Tool syntax in text response");
            if(!content.startsWith("{") && !content.startsWith("[")) {
                // The host attaches only evidence actually admitted in this run; the model need not copy opaque IDs.
                var ids=context.observations().stream().map(ToolResult::evidenceId).filter(Objects::nonNull).distinct().toList();
                return Decision.answer(content,ids);
            }
            result=client.toolArguments(content);
            return switch (text(result, "kind")) {
                case "CLARIFY" -> { fields(result, "kind", "clarification"); yield Decision.clarify(text(result, "clarification")); }
                case "ANSWER" -> {
                    fields(result, "kind", "answer"); JsonNode answer = result.path("answer"); fields(answer, "text", "evidenceIds");
                    if (!answer.path("evidenceIds").isArray()) throw new IllegalArgumentException("Invalid evidence IDs");
                    List<String> ids = new ArrayList<>();
                    for (JsonNode id : answer.path("evidenceIds")) { if (!id.isTextual()) throw new IllegalArgumentException("Invalid evidence ID"); ids.add(id.textValue()); }
                    yield Decision.answer(text(answer, "text"), ids);
                }
                default -> throw new IllegalArgumentException("Unknown decision");
            };
        } catch (RuntimeException e) { throw new AgentException("INVALID_DECISION", "Invalid structured model decision"); }
    }
}
