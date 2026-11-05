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
        List<Map<String,Object>> messages=assemble(instruction,context,history,working,calls);
        while(bytes(messages)>policy.maxInputBytes() && !history.isEmpty()) {
            removeOldestTurn(history);messages=assemble(instruction,context,history,working,calls);
        }
        bounds.check(messages);
        TraceAttributes.apply(Span.current(),Map.of("context.format","native_roles_v1","context.output_bytes",bytes(messages),"context.history_bytes",bytes(history),
                "context.history_messages",history.size(),"context.schemas_removed",schemas,
                "context.byte_limit",policy.maxInputBytes()));
        return List.copyOf(messages);
    }
    private void removeOldestTurn(List<MemoryTypes.Message> history) {
        if(history.isEmpty())return;
        UUID first=history.get(0).runId();
        do { history.remove(0); } while(!history.isEmpty() && Objects.equals(first,history.get(0).runId()));
    }
    private List<Map<String,Object>> assemble(String instruction,ModelContext context,List<MemoryTypes.Message> history,ObjectNode working,List<ToolCall> recordedCalls) {
        List<Map<String,Object>> out=new ArrayList<>();out.add(message("system",instruction));
        for(var prior:history) if(Set.of("user","assistant").contains(prior.role())) out.add(message(prior.role(),prior.text()));
        StringBuilder control=new StringBuilder();
        if(context.memory().environment()!=null) {
            var page=context.memory().environment();Map<String,Object> environment=new LinkedHashMap<>();environment.put("videoId",page.videoId());
            if(!page.videoTitle().isBlank())environment.put("videoTitle",page.videoTitle());
            if(page.playbackMs()!=null)environment.put("playbackMs",page.playbackMs());
            control.append(section("environment",environment)).append('\n');
        }
        var focus=context.memory().focus();
        String current=context.task()==null ? context.message():context.task().originalQuery();
        if(focus!=null && !focus.request().equals(current))control.append(section("current_focus",focus)).append('\n');
        if(context.task()!=null && !Set.of("conversation","unclassified").contains(context.task().specification().type()))
            control.append(section("confirmed_conditions",context.task().specification())).append('\n');
        Map<String,Object> runtime=new LinkedHashMap<>();runtime.put("businessDate",context.businessDate().toString());runtime.put("remainingSteps",context.remainingSteps());
        if(!context.activeSkills().isEmpty())runtime.put("activeSkills",context.activeSkills());
        if(context.memory().truncated() || history.size()<context.memory().recent().size())runtime.put("olderHistoryAvailable",true);
        if(!history.isEmpty())runtime.put("historyBefore",history.get(0).sequence());
        control.append(section("runtime",runtime)).append('\n');
        out.add(message("system",control.toString()));out.add(message("user",current));
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
            out.add(Map.of("role","assistant","content","","tool_calls",calls));out.addAll(replies);i=end;
        }
        if(context.reportState()!=null)out.add(message("system",section("task_state",context.reportState())));
        if(context.finalizing())out.add(message("system",context.systemDirective()));
        return out;
    }
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
                calls.set(i,new ToolCall(call.name(),call.arguments(),id,group));
            }
        }
        return List.copyOf(calls);
    }
    private AgentException invalidHistory() { return new AgentException("INVALID_MODEL_CONTEXT","Tool calls and results have inconsistent execution records"); }
}
