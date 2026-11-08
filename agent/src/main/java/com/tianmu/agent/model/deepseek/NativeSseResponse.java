package com.tianmu.agent.model.deepseek;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tianmu.agent.core.AgentException;
import java.util.*;
import java.util.function.Consumer;

/** Reassembles native tool calls; only content deltas are suitable for provisional display. */
final class NativeSseResponse {
    private final ObjectMapper json;private final Consumer<String> sink;
    private final StringBuilder content=new StringBuilder();private final Map<Integer,ObjectNode> tools=new TreeMap<>();
    private final StringBuilder reasoning=new StringBuilder();
    private final StringBuilder pending=new StringBuilder();private long flushedAt=System.nanoTime();private Boolean display;
    boolean done(){return done;}
    private void flush(){if(!pending.isEmpty()){sink.accept(pending.toString());pending.setLength(0);flushedAt=System.nanoTime();}}
    private JsonNode usage;private String finish,model;private boolean done;
    NativeSseResponse(ObjectMapper json,Consumer<String> sink){this.json=json;this.sink=sink;}
    void accept(String data) {
        if(done)throw new AgentException("MODEL_ERROR","Data after stream completion");
        if(data.equals("[DONE]")){done=true;flush();return;}
        try {
            JsonNode chunk=json.readTree(data);if(chunk.has("error"))throw new IllegalArgumentException();
            if(chunk.path("model").isTextual())model=chunk.path("model").asText();
            if(chunk.hasNonNull("usage"))usage=chunk.get("usage");
            for(var choice:chunk.path("choices")){
                if(choice.path("index").asInt()!=0)continue;
                if(choice.hasNonNull("finish_reason"))finish=choice.get("finish_reason").asText();
                var delta=choice.path("delta");
                if(delta.path("reasoning_content").isTextual()){
                    reasoning.append(delta.path("reasoning_content").asText());if(reasoning.length()>131072)throw new IllegalArgumentException();
                }
                if(delta.path("content").isTextual()){
                    String text=delta.path("content").asText();if(content.length()+text.length()>131072)throw new IllegalArgumentException();
                    content.append(text);if(display==null && !content.toString().isBlank()){String prefix=content.toString().stripLeading();display=!prefix.startsWith("{") && !prefix.startsWith("[");}
                    if(!Boolean.FALSE.equals(display)){pending.append(text);if(Boolean.TRUE.equals(display) && (content.length()==text.length() || pending.length()>=128 || System.nanoTime()-flushedAt>=50_000_000))flush();}else pending.setLength(0);
                }
                for(var call:delta.path("tool_calls")){
                    if(!call.path("index").isIntegralNumber())throw new IllegalArgumentException();int index=call.path("index").asInt(-1);if(index<0||index>3)throw new IllegalArgumentException();
                    ObjectNode target=tools.computeIfAbsent(index,i->{var node=json.createObjectNode();node.put("type","function");node.set("function",json.createObjectNode().put("name","").put("arguments",""));return node;});
                    if(call.hasNonNull("id"))target.put("id",call.get("id").asText());
                    var function=(ObjectNode)target.get("function");
                    for(String key:List.of("name","arguments"))if(call.path("function").path(key).isTextual()){
                        String value=function.path(key).asText()+call.path("function").path(key).asText();if(value.length()>65536)throw new IllegalArgumentException();function.put(key,value);
                    }
                }
            }
        }catch(AgentException e){throw e;}catch(Exception invalid){throw new AgentException("MODEL_ERROR","Invalid model stream");}
    }
    JsonNode result(){
        if(!done||finish==null)throw new AgentException("MODEL_ERROR","Model stream ended before completion");
        for(int i=0;i<tools.size();i++)if(!tools.containsKey(i) || !tools.get(i).path("id").isTextual() || tools.get(i).path("function").path("name").asText().isBlank())throw new AgentException("MODEL_ERROR","Incomplete tool stream");
        var message=json.createObjectNode().put("content",content.toString());if(!reasoning.isEmpty())message.put("reasoning_content",reasoning.toString());if(!tools.isEmpty())message.set("tool_calls",json.valueToTree(tools.values()));
        var result=json.createObjectNode();var choice=json.createObjectNode().put("finish_reason",finish);choice.set("message",message);result.set("choices",json.createArrayNode().add(choice));if(usage!=null)result.set("usage",usage);if(model!=null)result.put("model",model);return result;
    }
}
