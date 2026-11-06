package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.trace.TraceAttributes;
import io.opentelemetry.api.trace.Span;
import java.util.*;

/** Native conversation roles; only the current decision's environment, capabilities and evidence are loaded. */
public final class HarnessContextBuilder {
    private final ObjectMapper json;
    private final ContextPolicy policy;
    private final ModelContextBuilder bounds;
    public HarnessContextBuilder(ObjectMapper json,ContextPolicy policy) {
        this.json=json.copy().findAndRegisterModules();this.policy=policy;this.bounds=new ModelContextBuilder(this.json,policy);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid context",e); }
    }
    private int bytes(Object value) { return encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
    private String section(String name,Object value) {
        // Data cannot close a host-defined section or impersonate another message role.
        String body=encode(value).replace("&","\\u0026").replace("<","\\u003c").replace(">","\\u003e");
        return "<"+name+">"+body+"</"+name+">";
    }
    private Map<String,Object> message(String role,String content) { return Map.of("role",role,"content",content); }
    public List<Map<String,Object>> build(String instruction,ModelContext context) {
        List<ToolCall> calls = calls(context);
        var memory=context.memory();
        List<MemoryTypes.Message> history=new ArrayList<>(memory.recent());
        if(ConversationTopics.switchesTopic(context.message()))history.clear();
        // A small recent window is the default; older messages remain available through read_history.
        while(!history.isEmpty() && (history.size()>4 || bytes(history)>Math.min(8192,policy.maxHistoryBytes()))) removeOldestTurn(history);
        ObjectNode working=json.createObjectNode();working.set("tools",json.valueToTree(context.availableTools()));
        working.set("results",json.valueToTree(context.observations()));
        int schemas=bounds.deduplicateSchemas(working);
        int compacted=new ToolResultProjector(json).compact((ArrayNode)working.get("results"),context.availableTools().stream().anyMatch(t->t.name().equals("read_result")));
        TraceAttributes.apply(Span.current(),Map.of("context.results_compacted",compacted,"context.compaction_segments",memory.compaction().size()));
        NativeContextProjector.Request projected=assemble(instruction,context,history,working,calls);
        while(projected.payloadBytes()>policy.maxInputBytes() && !history.isEmpty()) {
            removeOldestTurn(history);projected=assemble(instruction,context,history,working,calls);
        }
        if(projected.payloadBytes()>policy.maxInputBytes())throw new AgentException("MODEL_CONTEXT_TOO_LARGE","Required context and tool schemas exceed the input budget");
        List<Map<String,Object>> messages=projected.messages();
        bounds.check(messages);
        TraceAttributes.apply(Span.current(),Map.of("context.format","context_envelope_v1","context.output_bytes",bytes(messages),"context.history_bytes",bytes(history),
                "context.history_messages",history.size(),"context.schemas_removed",schemas,
                "context.byte_limit",policy.maxInputBytes()));
        TraceAttributes.apply(Span.current(),Map.of("context.schema_version","1.0","context.projection_version","chat_native_v1",
                "context.present_slots",new ArrayList<>(projected.slotBytes().keySet()),"context.slot_bytes",projected.slotBytes().entrySet().stream().map(e->e.getKey()+":"+e.getValue()).toList()));
        return List.copyOf(messages);
    }
    private void removeOldestTurn(List<MemoryTypes.Message> history) {
        if(history.isEmpty())return;
        UUID first=history.get(0).runId();
        do { history.remove(0); } while(!history.isEmpty() && Objects.equals(first,history.get(0).runId()));
    }
    private NativeContextProjector.Request assemble(String instruction,ModelContext context,List<MemoryTypes.Message> history,ObjectNode working,List<ToolCall> recordedCalls) {
        List<Map<String,Object>> out=new ArrayList<>();
        for(var prior:history) if(Set.of("user","assistant").contains(prior.role())) out.add(message(prior.role(),prior.text()));
        var parts=new ArrayList<ContextEnvelope.Part>();
        parts.add(part(ContextEnvelope.Slot.SYSTEM_PROMPT,Map.of("text",instruction+"\n用户负向情绪只影响回应语气，不改变事实、任务条件或权限。能力识别仅描述目标，关联目标不自动增加分析来源，仍按注册能力和确认条件执行。")));
        if(!context.availableTools().isEmpty())parts.add(part(ContextEnvelope.Slot.TOOL_CATALOG,Map.of("tools",data(context.availableTools()))));
        var index=new TreeMap<String,Map<String,Object>>();
        if(context.preparation()!=null && context.preparation().recognition()!=null)
            for(var candidate:context.preparation().recognition().candidates())if(candidate.skill()!=null)
                index.put(candidate.skill(),Map.of("name",candidate.skill(),"description",candidate.description(),"active",context.activeSkills().contains(candidate.skill())));
        for(String name:context.activeSkills())index.putIfAbsent(name,Map.of("name",name,"active",true));
        if(!index.isEmpty())parts.add(part(ContextEnvelope.Slot.SKILL_CATALOG,Map.of("skills",new ArrayList<>(index.values()))));
        parts.add(part(ContextEnvelope.Slot.EXECUTION_CONTEXT,Map.of("business_date",context.businessDate().toString(),"timezone","Asia/Shanghai")));
        Map<String,Object> focus=new LinkedHashMap<>();
        var previous=context.memory().focus();
        String current=context.task()!=null ? context.task().originalQuery()
                : context.preparation()!=null ? context.preparation().originalQuery() : context.message();
        if(previous!=null && !previous.request().equals(current))focus.put("current_focus",data(previous));
        if(context.preparation()!=null) {
            var prep=context.preparation();
            focus.put("request_preparation",Map.of("intent",prep.intent().name(),"emotion",prep.emotion().name(),"degraded_stages",new ArrayList<>(prep.degradedStages())));
            if(!prep.query().equals(current))focus.put("execution_query",prep.query());
            if(prep.recognition()!=null && prep.recognition().primary()!=null)focus.put("recognized_capabilities",Map.of("primary",prep.recognition().primary(),"related",prep.recognition().related()));
        }
        if(context.task()!=null && !Set.of("conversation","unclassified").contains(context.task().specification().type()))focus.put("confirmed_conditions",data(context.task().specification()));
        if(!focus.isEmpty())parts.add(part(ContextEnvelope.Slot.FOCUS,focus));
        if(!context.memory().persistent().isEmpty())parts.add(part(ContextEnvelope.Slot.MEMORY_CONTEXT,Map.of("items",data(context.memory().persistent()))));
        if(context.memory().environment()!=null) {
            var page=context.memory().environment();Map<String,Object> environment=new LinkedHashMap<>();environment.put("videoId",page.videoId());
            if(!page.videoTitle().isBlank())environment.put("videoTitle",page.videoTitle());
            if(page.playbackMs()!=null)environment.put("playbackMs",page.playbackMs());
            parts.add(part(ContextEnvelope.Slot.ENVIRONMENT_CONTEXT,environment));
        }
        Map<String,Object> runtime=new LinkedHashMap<>();runtime.put("businessDate",context.businessDate().toString());runtime.put("remainingSteps",context.remainingSteps());
        if(!context.activeSkills().isEmpty())runtime.put("activeSkills",new ArrayList<>(context.activeSkills()));
        if(context.memory().truncated() || history.size()<context.memory().recent().size())runtime.put("olderHistoryAvailable",true);
        if(!history.isEmpty())runtime.put("historyBefore",history.get(0).sequence());
        if(context.reportState()!=null)runtime.put("task_state",data(context.reportState()));
        parts.add(part(ContextEnvelope.Slot.RUNTIME_STATE,runtime));
        if(context.finalizing())parts.add(part(ContextEnvelope.Slot.OUTPUT_CONTRACT,Map.of("mode","finalization","instruction",context.systemDirective())));
        out.add(message("user",current));
        ArrayNode results=(ArrayNode)working.get("results");
        for(int i=0;i<results.size();) {
            ToolCall first=recordedCalls.get(i);int end=i+1;
            if(first.providerGroupId()!=null)while(end<results.size() && first.providerGroupId().equals(recordedCalls.get(end).providerGroupId()))end++;
            List<Map<String,Object>> calls=new ArrayList<>();List<Map<String,Object>> replies=new ArrayList<>();
            for(int j=i;j<end;j++) {
                ToolCall call=recordedCalls.get(j);String id=call.providerCallId();
                calls.add(Map.of("id",id,"type","function","function",Map.of("name",call.name(),"arguments",encode(call.arguments()))));
                ObjectNode result=((ObjectNode)results.get(j)).deepCopy();result.remove("terminalAnswer");
                replies.add(Map.of("role","tool","tool_call_id",id,"content",encode(result)));
            }
            for(int j=i+1;j<end;j++)if(!first.assistantText().equals(recordedCalls.get(j).assistantText()))throw invalidHistory();
            out.add(Map.of("role","assistant","content",first.assistantText(),"tool_calls",calls));out.addAll(replies);i=end;
        }
        var conversation=new LinkedHashMap<String,Object>();conversation.put("messages",out);
        if(!context.memory().compaction().isEmpty() && !ConversationTopics.switchesTopic(context.message()))conversation.put("compaction",Map.of("segments",data(context.memory().compaction()),"completeness","PARTIAL_READBACK_AVAILABLE"));
        parts.add(part(ContextEnvelope.Slot.CURRENT_CONTEXT,conversation));
        var envelope=new ContextEnvelope("1.0",context.finalizing()?ContextEnvelope.Phase.FINALIZATION:ContextEnvelope.Phase.LOOP,"context-v1",parts);
        return new NativeContextProjector(json,policy).project(envelope,false);
    }
    private Object data(Object value){return json.convertValue(value,Object.class);}
    private ContextEnvelope.Part part(ContextEnvelope.Slot slot,Map<String,Object> payload){return new ContextEnvelope.Part(slot,"runtime-v1",payload);}
    /** Execution order is the association key; equal result bodies are not identifiers. */
    private List<ToolCall> calls(ModelContext context) {
        boolean legacy=context.exchanges().isEmpty();
        if(!legacy && context.exchanges().size()!=context.observations().size())throw invalidHistory();
        List<ToolCall> calls=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<context.observations().size();i++) {
            ToolResult observed=context.observations().get(i);
            ToolCall call;
            if(legacy)call=new ToolCall(observed.tool(),Map.of());
            else {
                ToolExchange exchange=context.exchanges().get(i);call=exchange.call();
                if(call==null || !observed.equals(exchange.result()) || !Objects.equals(call.name(),observed.tool()))throw invalidHistory();
            }
            if(call.name()==null || call.name().isBlank())throw invalidHistory();
            String id=call.providerCallId();
            if(id!=null && (id.length()>160 || !id.matches("[A-Za-z0-9_.:-]+") || !ids.add(id)))throw invalidHistory();
            calls.add(call);
        }
        Set<String> closedGroups=new HashSet<>();String group=null;int groupSize=0;
        for(int i=0;i<calls.size();i++) {
            ToolCall call=calls.get(i);String nextGroup=call.providerGroupId();
            if(!Objects.equals(group,nextGroup)) {
                if(group!=null)closedGroups.add(group);
                if(nextGroup!=null && (nextGroup.isBlank() || closedGroups.contains(nextGroup)))throw invalidHistory();
                group=nextGroup;groupSize=0;
            }
            if(group!=null && ++groupSize>4)throw invalidHistory();
            if(call.providerCallId()==null) {
                String base="call_host_"+i,id=base;int suffix=0;
                while(!ids.add(id))id=base+"_"+(++suffix);
                calls.set(i,new ToolCall(call.name(),call.arguments(),id,group,call.assistantText()));
            }
        }
        return List.copyOf(calls);
    }
    private AgentException invalidHistory() { return new AgentException("INVALID_MODEL_CONTEXT","Tool calls and results have inconsistent execution records"); }
}
