package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.Input;
import java.util.*;
import java.util.function.ToIntFunction;

/** The same bounded material is measured by the planner and sent by the model adapter. */
public final class FeedbackSynthesis {
    private FeedbackSynthesis() {}
    // Bump when changing packing semantics; accepted reports retain their original policy hash.
    public static final String VERSION="synthesis-material-v2";
    public static final int MAX_CHILDREN=32, MAX_PAYLOAD_BYTES=24000;
    public record Material(Map<String,Object> payload,Map<String,String> references) {}
    public static Material material(List<Topic> topics){
        var references=new LinkedHashMap<String,String>();var children=new ArrayList<Map<String,Object>>();
        for(var topic:topics){
            String alias="C"+(children.size()+1);references.put(alias,topic.id());
            var row=new LinkedHashMap<String,Object>();row.put("id",alias);row.put("title",topic.title());
            row.put("observation",topic.observation());row.put("recommendation",topic.recommendation());
            row.put("messages",topic.messages());row.put("author_messages",topic.authorMessages());
            children.add(Collections.unmodifiableMap(row));
        }
        var payload=new LinkedHashMap<String,Object>();payload.put("kind",WorkKind.MERGE);payload.put("max_topics",1);
        payload.put("children",List.copyOf(children));payload.put("expected_input_ids",List.copyOf(references.keySet()));
        return new Material(Collections.unmodifiableMap(payload),Collections.unmodifiableMap(references));
    }
    public static List<Input> batches(List<Topic> topics,ToIntFunction<Object> bytes){
        var batches=new ArrayList<Input>();var part=new ArrayList<Topic>();
        for(var topic:topics){
            part.add(topic);
            if(part.size()>MAX_CHILDREN || bytes.applyAsInt(material(part).payload())>MAX_PAYLOAD_BYTES){
                part.remove(part.size()-1);
                if(part.isEmpty())throw tooLarge();
                batches.add(input(part));part=new ArrayList<>();part.add(topic);
                if(bytes.applyAsInt(material(part).payload())>MAX_PAYLOAD_BYTES)throw tooLarge();
            }
        }
        if(!part.isEmpty())batches.add(input(part));
        if(topics.size()>1 && batches.size()==topics.size())throw tooLarge();
        return List.copyOf(batches);
    }
    private static Input input(List<Topic> topics){return new Input(WorkKind.MERGE,List.of(),topics,null);}
    private static AgentException tooLarge(){return new AgentException("FULL_ANALYSIS_TOO_LARGE","Observations cannot be reduced within the synthesis input budget");}
}
