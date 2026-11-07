package com.tianmu.agent.core;

import java.time.Instant;
import java.util.*;

/** Read-only provenance; candidate metadata and historical reports never count as current full analysis. */
public record LookupFacts(String kind,String operation,Map<String,Object> data,Set<Long> videoIds,Instant readAt){
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public static Map<String,Object> normalize(Map<String,?> data){
        try{
            byte[] bytes=JSON.writeValueAsBytes(data);if(bytes.length>32768)throw new IllegalArgumentException("Resource page exceeds 32 KiB");
            return ImmutablePayload.map(JSON.readValue(bytes,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid resource data",invalid);}
    }
    public LookupFacts(String operation,Map<String,Object> data,Set<Long> videos){this("resource-lookup-v1",operation,data,videos,Instant.now());}
    public LookupFacts{
        data=normalize(data);videoIds=Set.copyOf(videoIds);
        if(!"resource-lookup-v1".equals(kind) || !Set.of("search_my_videos","read_report","analyze_comments","analyze_danmaku","prepare_feedback_materials").contains(operation) || videoIds.size()>20 || readAt==null)throw new IllegalArgumentException("Invalid resource provenance");
    }
}
