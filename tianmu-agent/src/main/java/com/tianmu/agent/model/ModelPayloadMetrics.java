package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Serialized byte accounting only; byte sizes are neither token counts nor additive billing units. */
public final class ModelPayloadMetrics {
    private ModelPayloadMetrics(){}
    public static Map<String,Object> measure(ObjectMapper json,Map<String,Object> request){
        var out=new LinkedHashMap<String,Object>();
        out.put("context.request_bytes",bytes(json,request));
        out.put("context.tools_bytes",bytes(json,request.getOrDefault("tools",List.of())));
        out.put("context.message_bytes",bytes(json,request.get("messages")));
        long system=0,user=0,assistant=0,tool=0,duplicates=0;var seen=new HashSet<String>();
        for(Object item:(List<?>)request.get("messages")){
            var message=(Map<?,?>)item;int n=bytes(json,message);
            switch(Objects.toString(message.get("role"),"")){
                case "system" -> system+=n;case "user" -> user+=n;case "assistant" -> assistant+=n;
                case "tool" -> {tool+=n;String content=Objects.toString(message.get("content"),"");if(!seen.add(content))duplicates+=bytes(json,content);}
                default -> {}
            }
        }
        out.put("context.system_bytes",system);out.put("context.user_bytes",user);out.put("context.assistant_bytes",assistant);
        out.put("context.tool_result_bytes",tool);out.put("context.duplicate_result_bytes",duplicates);
        return Map.copyOf(out);
    }
    private static int bytes(ObjectMapper json,Object value){try{return json.writeValueAsBytes(value).length;}catch(Exception e){throw new IllegalArgumentException("Cannot measure model request",e);}}
}
