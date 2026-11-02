package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.CallControl;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component @Profile("deepseek")
public final class DeepSeekFeedbackModels implements FeedbackModels {
    private final DeepSeekClient client;private final DeepSeekProperties config;private final ObjectMapper json;
    private final String instruction,hash;
    public DeepSeekFeedbackModels(DeepSeekClient client,DeepSeekProperties config,ObjectMapper json,PromptCatalog prompts){
        this.client=client;this.config=config;this.json=json;instruction=prompts.text("feedback.analysis");hash=FeedbackObjects.sha(instruction.getBytes(StandardCharsets.UTF_8));
    }
    public String modelName(){return config.harnessModel();}
    public String policyHash(){return hash;}
    public List<Proposal> analyze(Input input,CallControl control){
        var payload=new LinkedHashMap<String,Object>();payload.put("kind",input.kind());
        payload.put("text_limits",Map.of("title",100,"observation",2400,"recommendation",1200));
        payload.put("target_lengths",Map.of("observation",800,"recommendation",400));
        if(input.correction()!=null){
            var c=input.correction();var previous=new LinkedHashMap<String,String>();
            for(var topic:c.previousTopics())for(String id:topic.members())previous.putIfAbsent(id,topic.key());
            payload.put("correction",Map.of("previous_topics",c.previousTopics().stream().map(t->Map.of("key",t.key(),"title",t.title(),"observation",t.observation(),"recommendation",t.recommendation())).toList(),
                    "previous_assignments",previous,"missing",c.missing(),"duplicates",c.duplicates(),"unknown",c.unknown(),"issue",c.issue()));
        }
        payload.put("max_topics",input.kind()==FeedbackAnalysis.WorkKind.CLASSIFY?10:Math.max(1,(input.children().size()+3)/4));
        if(input.kind()==FeedbackAnalysis.WorkKind.CLASSIFY)payload.put("groups",input.groups().stream().map(g->{
            var m=new LinkedHashMap<String,Object>();m.put("id",g.id());m.put("source",g.source());m.put("text",g.text());m.put("reply_context",g.replyContext());
            m.put("author",g.author());m.put("playback_ms",g.playbackMs());m.put("count",g.messages());return m;
        }).toList());
        else payload.put("children",input.children().stream().map(t->Map.of("id",t.id(),"title",t.title(),"observation",t.observation(),"recommendation",t.recommendation(),"messages",t.messages(),"author_messages",t.authorMessages())).toList());
        if(input.video()!=null)payload.put("video",Map.of("summary",input.video().summary(),"moments",input.video().moments(),"sampledAtMs",input.video().sampledAtMs(),"coverage",input.video().coverage(),"transcript",input.video().transcript()));
        var body=client.complete(config.harnessModel(),client.messages(instruction,payload),6144,control,true);
        try {
            if(!body.isObject() || body.size()!=2 || !body.path("topics").isArray() || body.path("topics").size()>10 || !body.path("assignments").isObject())throw new IllegalArgumentException();
            var rows=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();var members=new LinkedHashMap<String,List<String>>();
            for(var row:body.get("topics")){
                if(!row.isObject() || row.size()!=4)throw new IllegalArgumentException();
                for(String field:List.of("key","title","observation","recommendation"))if(!row.path(field).isTextual())throw new IllegalArgumentException();
                String key=row.get("key").asText();if(rows.putIfAbsent(key,row)!=null)throw new IllegalArgumentException();members.put(key,new ArrayList<>());
            }
            var assignments=body.get("assignments").fields();
            while(assignments.hasNext()){
                var entry=assignments.next();var value=entry.getValue();
                if(!value.isTextual() || !members.containsKey(value.asText()))throw new IllegalArgumentException();
                members.get(value.asText()).add(entry.getKey());
            }
            var out=new ArrayList<Proposal>();
            rows.forEach((key,row)->out.add(new Proposal(key,row.get("title").asText(),row.get("observation").asText(),row.get("recommendation").asText(),members.get(key))));
            return List.copyOf(out);
        } catch(RuntimeException invalid){throw new AgentException("INVALID_ANALYSIS","Provider returned an invalid bounded classification");}
    }
}
