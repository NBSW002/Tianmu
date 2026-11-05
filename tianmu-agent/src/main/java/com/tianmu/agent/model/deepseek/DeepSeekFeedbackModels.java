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
    private final String instruction,hash,pipelineInstruction,pipelineHash;
    public DeepSeekFeedbackModels(DeepSeekClient client,DeepSeekProperties config,ObjectMapper json,PromptCatalog prompts){
        this.client=client;this.config=config;this.json=json;instruction=prompts.text("feedback.analysis");hash=FeedbackObjects.sha(instruction.getBytes(StandardCharsets.UTF_8));
        pipelineInstruction=prompts.text("feedback.pipeline");pipelineHash=FeedbackObjects.sha(pipelineInstruction.getBytes(StandardCharsets.UTF_8));
    }
    public String modelName(){return config.analysisModel();}
    public String policyHash(){return hash;}
    public List<Proposal> analyze(Input input,CallControl control){
        if(input.kind()!=FeedbackAnalysis.WorkKind.CLASSIFY)throw new IllegalArgumentException("Topic aggregation is code-only");
        var payload=new LinkedHashMap<String,Object>();payload.put("kind",input.kind());
        payload.put("text_limits",Map.of("title",100,"observation",2400,"recommendation",1200));
        payload.put("target_lengths",Map.of("observation",800,"recommendation",400));
        payload.put("max_topics",10);
        if(input.kind()==FeedbackAnalysis.WorkKind.CLASSIFY && !input.knownTopics().isEmpty())payload.put("known_topics",input.knownTopics().stream().map(t->Map.of(
                "key",t.key(),"title",t.title(),"messages",t.messages())).toList());
        payload.put("groups",input.groups().stream().map(g->{
            var m=new LinkedHashMap<String,Object>();m.put("id",g.id());m.put("source",g.source());m.put("text",g.text());m.put("reply_context",g.replyContext());
            m.put("author",g.author());m.put("playback_ms",g.playbackMs());m.put("count",g.messages());return m;
        }).toList());
        if(input.video()!=null)payload.put("video",Map.of("summary",input.video().summary(),"moments",input.video().moments(),"sampledAtMs",input.video().sampledAtMs(),"coverage",input.video().coverage(),"transcript",input.video().transcript()));
        var body=complete(input,payload,control);
        return parse(body,10);
    }
    private List<Proposal> parse(com.fasterxml.jackson.databind.JsonNode body,int maxTopics){
        try {
            // Flash may echo the JSON-mode format marker. It is fixed protocol metadata, not evidence.
            boolean formatMarker=body.size()==3 && body.path("type").isTextual() && "json_object".equals(body.path("type").asText());
            if(!body.isObject() || !(body.size()==2 || formatMarker) || !body.path("topics").isArray() || body.path("topics").size()>maxTopics || !body.path("assignments").isObject())throw new IllegalArgumentException();
            if(formatMarker)com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("model.response_repaired",true));
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
            // The model may echo known catalog entries with no current assignments. Such declarations
            // carry no evidence and must not become batch notes or force another paid classification.
            // Every supplied assignment still passes the planner's exact coverage/identity checks.
            rows.forEach((key,row)->{if(!members.get(key).isEmpty())out.add(new Proposal(key,row.get("title").asText(),row.get("observation").asText(),row.get("recommendation").asText(),members.get(key)));});
            return List.copyOf(out);
        } catch(RuntimeException invalid){throw new AgentException("INVALID_ANALYSIS","Provider returned an invalid bounded classification");}
    }
    public String pipelinePolicyHash(){return pipelineHash;}
    public List<Proposal> analyzePipeline(Input input,CallControl control){
        var payload=new LinkedHashMap<String,Object>();payload.put("kind",input.kind());
        int max=input.kind()==FeedbackAnalysis.WorkKind.MERGE?1:10;payload.put("max_topics",max);
        if(input.kind()==FeedbackAnalysis.WorkKind.CLASSIFY)payload.put("groups",input.groups().stream().map(g->{
            var row=new LinkedHashMap<String,Object>();row.put("id",g.id());row.put("source",g.source());row.put("text",g.text());
            row.put("reply_context",g.replyContext());row.put("author",g.author());row.put("playback_ms",g.playbackMs());row.put("count",g.messages());row.put("candidate_cluster",g.candidateCluster());return row;
        }).toList());
        else payload.put("children",input.children());
        if(input.video()!=null)payload.put("video",Map.of("summary",input.video().summary(),"moments",input.video().moments(),"sampledAtMs",input.video().sampledAtMs(),"coverage",input.video().coverage(),"transcript",input.video().transcript()));
        if(input.correction()!=null)payload.put("correction",Map.of("missing",input.correction().missing(),"duplicates",input.correction().duplicates(),"issue",input.correction().issue()));
        return parse(client.complete(config.analysisModel(),client.messages(pipelineInstruction,payload),6144,control,true),max);
    }
    private com.fasterxml.jackson.databind.JsonNode complete(Input input,Map<String,Object> payload,CallControl control){
        if(input.correction()==null)return client.complete(config.analysisModel(),client.messages(instruction,payload),6144,control,true);
        var c=input.correction();var expected=new HashSet<String>();
        input.groups().forEach(g->expected.add(g.id()));input.children().forEach(t->expected.add(t.id()));
        var previous=new LinkedHashMap<String,String>();
        for(var topic:c.previousTopics())if(topic.key()!=null && topic.key().matches("T(?:[1-9]|[1-9][0-9]|100)"))
            for(String id:topic.members())if(expected.contains(id))previous.putIfAbsent(id,topic.key());
        // Previous prose is already durable in the rejected input. It is optional retry guidance, never new evidence.
        payload.put("correction",Map.of("previous_assignments",previous,
                "missing",c.missing().stream().filter(expected::contains).toList(),
                "duplicates",c.duplicates().stream().filter(expected::contains).toList(),
                "unknown_count",c.unknown().size(),"issue",c.issue()));
        for(int detail=0;;detail++){
            try { return client.complete(config.analysisModel(),client.messages(instruction,payload),6144,control,true); }
            catch(AgentException tooLarge){
                if(!"MODEL_CONTEXT_TOO_LARGE".equals(tooLarge.code()) || detail==2)throw tooLarge;
                // Both messages and the full provider envelope are guarded before any network call or model charge.
                // Keep all source/video facts; reduce optional diagnostics, then regenerate from the original input.
                if(detail==0)payload.put("correction",Map.of("issue",c.issue()));else payload.remove("correction");
            }
        }
    }
}
