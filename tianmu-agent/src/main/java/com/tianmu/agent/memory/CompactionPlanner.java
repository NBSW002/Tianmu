package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure planning and validation. Never splits a completed turn or trusts model-supplied source metadata. */
public final class CompactionPlanner {
    public record Plan(List<MemoryTypes.Message> messages,String fingerprint,int inputBytes){
        public Plan{messages=List.copyOf(messages);}
        public long from(){return messages.get(0).sequence();}
        public long through(){return messages.get(messages.size()-1).sequence();}
    }
    private final ObjectMapper json;
    public CompactionPlanner(ObjectMapper json){this.json=json.copy().findAndRegisterModules();}
    public int bytes(Object value){try{return json.writeValueAsBytes(value).length;}catch(Exception e){throw new IllegalArgumentException("Invalid compaction data",e);}}
    public Plan plan(List<MemoryTypes.Message> messages,int triggerBytes,int maxInputBytes){
        var groups=new LinkedHashMap<UUID,List<MemoryTypes.Message>>();
        messages.stream().sorted(Comparator.comparingLong(MemoryTypes.Message::sequence)).forEach(m->groups.computeIfAbsent(m.runId(),k->new ArrayList<>()).add(m));
        var selected=new ArrayList<MemoryTypes.Message>();
        for(var group:groups.values()){
            if(group.size()!=2 || !"user".equals(group.get(0).role()) || !"assistant".equals(group.get(1).role()))break;
            if(group.get(1).sequence()!=group.get(0).sequence()+1 || !selected.isEmpty() && group.get(0).sequence()!=selected.get(selected.size()-1).sequence()+1)break;
            var candidate=new ArrayList<>(selected);candidate.addAll(group);if(bytes(candidate)>maxInputBytes)break;selected=candidate;
        }
        if(selected.size()<4 || bytes(selected)<triggerBytes)return null;
        try{byte[] raw=json.writeValueAsBytes(selected);return new Plan(selected,com.tianmu.agent.adapters.storage.FileObjectStore.hash(raw),raw.length);}
        catch(java.io.IOException e){throw new IllegalArgumentException(e);}
    }
    public Map<String,Object> validate(JsonNode result,Plan plan,int maxBytes){
        if(!result.isObject() || result.size()!=3 || !result.path("synopsis").isTextual() || result.path("synopsis").asText().isBlank() || !result.path("claims").isArray() || !result.path("pending_work").isArray() || result.path("claims").size()>16 || result.path("pending_work").size()>8 || bytes(result)>maxBytes || bytes(result)>=plan.inputBytes())throw invalid();
        Set<String> ids=new HashSet<>();plan.messages().forEach(m->ids.add(m.id().toString()));
        for(var claim:result.path("claims")){
            if(claim.size()!=3 || !Set.of("decision","constraint","fact","open_question","rejected_option").contains(claim.path("type").asText()) || !claim.path("text").isTextual() || claim.path("text").asText().isBlank() || !claim.path("source_refs").isArray() || claim.path("source_refs").isEmpty())throw invalid();
            for(var ref:claim.path("source_refs"))if(!ref.isTextual() || !ids.contains(ref.asText()))throw invalid();
        }
        for(var work:result.path("pending_work"))if(!work.isTextual() || work.asText().isBlank() || work.asText().length()>300)throw invalid();
        return json.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    private AgentException invalid(){return new AgentException("INVALID_MODEL_RESPONSE","Invalid or ineffective compaction summary");}
}
