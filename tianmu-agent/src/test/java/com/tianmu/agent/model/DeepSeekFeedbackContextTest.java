package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Real request serialization and local HTTP only: no model service or paid calls. */
class DeepSeekFeedbackContextTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    HttpServer server;
    Map<String,Object> extraFields=Map.of();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));
            var result=new LinkedHashMap<String,Object>(Map.of("topics",List.of(Map.of("key","T1","title","反馈","observation","意见","recommendation","")),"assignments",Map.of("G1","T1")));
            result.putAll(extraFields);
            byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(result))))));
            exchange.sendResponseHeaders(200,response.length);try(var out=exchange.getResponseBody()){out.write(response);}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    DeepSeekFeedbackModels adapter(ContextPolicy policy){
        var config=new DeepSeekProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"local-test","fixture","fixture",Duration.ofSeconds(2),384,3072,"analysis-only");
        return new DeepSeekFeedbackModels(new DeepSeekClient(config,json,policy),config,json,new PromptCatalog());
    }
    CallControl control(){return new CallControl(Instant.now().plusSeconds(15),new CancellationToken());}
    @Test void flashFormatMarkerDoesNotDiscardAnOtherwiseValidClassification(){
        var model=adapter(ContextPolicy.defaults());var original=model.analyze(input(false),control());
        extraFields=Map.of("type","json_object");
        assertEquals(original,model.analyze(input(false),control()),"Only the fixed marker is ignored; all assignments and prose remain intact");
        for(var extra:List.<Map<String,Object>>of(Map.of("type","other"),Map.of("unexpected","json_object"),Map.of("type","json_object","extra",true))){
            extraFields=extra;
            assertEquals("INVALID_ANALYSIS",assertThrows(AgentException.class,()->model.analyze(input(false),control())).code());
        }
    }
    @Test void classificationUsesIndependentAnalysisModel(){
        var model=adapter(ContextPolicy.defaults());model.analyze(input(false),control());
        assertEquals("analysis-only",model.modelName());
        assertEquals("analysis-only",requests.get(0).path("model").asText());
    }
    @Test void pipelineKeepsEveryExpressionAndCandidateIdentityWithoutHistoricalTopics()throws Exception {
        var groups=List.of(new Group("G1",Source.COMMENT,"没声音","",false,null,12,List.of("C:1"),"K1"),
                new Group("G2",Source.COMMENT,"声音很好","",false,null,1,List.of("C:2"),"K1"),
                new Group("G3",Source.COMMENT,"😀","",false,null,1,List.of("C:3"),"K2"));
        extraFields=Map.of("assignments",Map.of("G1","T1","G2","T1","G3","T1"));
        var model=adapter(ContextPolicy.defaults());
        model.analyzePipeline(new Input(WorkKind.CLASSIFY,groups,List.of(),null,null,List.of(new KnownTopic("T8","旧主题",40))),control());
        var sent=payload(0);assertEquals(3,sent.path("groups").size());assertFalse(sent.has("known_topics"));assertFalse(sent.has("video"));
        assertEquals("没声音",sent.path("groups").get(0).path("text").asText());
        assertEquals("声音很好",sent.path("groups").get(1).path("text").asText());
        assertEquals("😀",sent.path("groups").get(2).path("text").asText());
        assertEquals("K1",sent.path("groups").get(1).path("candidate_cluster").asText());
        assertEquals(12,sent.path("groups").get(0).path("count").asInt());
        assertNotEquals(model.policyHash(),model.pipelinePolicyHash());
    }
    @Test void pipelineMergeCarriesChildReferencesAndEnforcesBoundedSynthesis()throws Exception {
        var children=List.of(new Topic("batch1:T1","声音问题","有人听不到","检查音轨",List.of("G1"),12,0),
                new Topic("batch2:T1","声音正常","另有人认为清楚","保留差异",List.of("G2"),1,0));
        extraFields=Map.of("assignments",Map.of("batch1:T1","T1","batch2:T1","T1"));
        var model=adapter(ContextPolicy.defaults());var input=new Input(WorkKind.MERGE,List.of(),children,null,null);
        var result=model.analyzePipeline(input,control());assertEquals(Set.of("batch1:T1","batch2:T1"),new HashSet<>(result.get(0).members()));
        assertEquals(json.readTree(json.writeValueAsString(children)),payload(0).get("children"));assertEquals(1,payload(0).path("max_topics").asInt());
        extraFields=Map.of("topics",List.of(Map.of("key","T1","title","a","observation","a","recommendation",""),
                Map.of("key","T2","title","b","observation","b","recommendation","")),"assignments",Map.of("batch1:T1","T1","batch2:T1","T2"));
        assertEquals("INVALID_ANALYSIS",assertThrows(AgentException.class,()->model.analyzePipeline(input,control())).code());
    }
    Input input(boolean correction){
        var times=new ArrayList<Long>();var moments=new ArrayList<VideoContent.Moment>();
        for(long t=0;t<12;t++){times.add(t);moments.add(new VideoContent.Moment(t,"画".repeat(600)));}
        var video=new VideoContent.Facts("video-content-v1",1001,"fixture",0,10000,"画".repeat(4000),moments,"声".repeat(12000),
                "SAMPLED_FRAMES_AND_AUDIO_TRANSCRIPT",times,"local",Instant.now());
        var prior=List.of(new Proposal("T1","一","观".repeat(2000),"建".repeat(500),List.of()),
                new Proposal("T2","二","观".repeat(2000),"建".repeat(500),List.of()));
        return new Input(WorkKind.CLASSIFY,List.of(new Group("G1",Source.COMMENT,"评".repeat(6000),"",false,null,1,List.of("C:1"))),List.of(),video,
                correction?new Correction(prior,List.of("G1"),List.of(),List.of()):null);
    }
    JsonNode payload(int index)throws Exception{return json.readTree(requests.get(index).path("messages").get(1).path("content").asText());}
    @Test void largeValidEvidenceCanBeCorrectedWithoutDuplicatingPreviousProse()throws Exception {
        var models=adapter(ContextPolicy.defaults());models.analyze(input(false),control());models.analyze(input(true),control());
        assertEquals(2,requests.size());var original=payload(0);var retried=payload(1);
        assertEquals(original.get("groups"),retried.get("groups"));assertEquals(original.get("video"),retried.get("video"));
        assertFalse(retried.path("correction").has("previous_topics"));
        assertEquals("G1",retried.path("correction").path("missing").get(0).asText());
        var oldPayload=(com.fasterxml.jackson.databind.node.ObjectNode)retried.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)oldPayload.get("correction")).set("previous_topics",json.valueToTree(input(true).correction().previousTopics()));
        assertEquals("MODEL_CONTEXT_TOO_LARGE",assertThrows(AgentException.class,()->new ModelContextBuilder(json,ContextPolicy.defaults())
                .build(new PromptCatalog().text("feedback.analysis"),json.convertValue(oldPayload,Map.class))).code());
    }
    @Test void optionalDiagnosticsCannotMakeAnOtherwiseFittingProviderEnvelopeFail()throws Exception {
        adapter(ContextPolicy.defaults()).analyze(input(false),control());
        int exactLimit=json.writeValueAsBytes(List.of(Map.of("request",requests.get(0)))).length;
        var tight=adapter(new ContextPolicy(exactLimit,0));
        tight.analyze(input(true),control());
        assertEquals(2,requests.size(),"All local context reductions happen before HTTP or model charging");
        assertFalse(payload(1).has("correction"));
        assertEquals(payload(0),payload(1),"Required source, video and output limits remain unchanged");
    }
    @Test void oversizedRequiredEvidenceStillFailsWithoutCallingTheProvider(){
        var models=adapter(new ContextPolicy(1024,0));
        assertEquals("MODEL_CONTEXT_TOO_LARGE",assertThrows(AgentException.class,()->models.analyze(input(true),control())).code());
        assertTrue(requests.isEmpty());
    }
    @Test void incrementalDirectoryProducesTheSameProviderEnvelopeAsLegacyFullTopics()throws Exception {
        var base=input(true);var prior=List.of(new Topic("T2","首次标题","完整观点","建议",List.of("batch:T2"),81,1),
                new Topic("T1","另一主题","不应传给模型的历史正文","",List.of("batch:T1"),42,0));
        var legacy=new Input(base.kind(),base.groups(),prior,base.video(),base.correction());
        var compact=new Input(base.kind(),base.groups(),List.of(),base.video(),base.correction(),
                List.of(new KnownTopic("T2","首次标题",81),new KnownTopic("T1","另一主题",42)));
        var models=adapter(ContextPolicy.defaults());models.analyze(legacy,control());models.analyze(compact,control());
        assertEquals(requests.get(0),requests.get(1),"Source, video, correction, catalog order and all provider options must remain identical");
        var persisted=json.valueToTree(compact);assertFalse(persisted.has("knownTopics"));assertTrue(persisted.path("children").isEmpty());
        var restored=json.treeToValue(persisted,Input.class);assertEquals(compact.correction(),restored.correction());assertTrue(restored.knownTopics().isEmpty());
        assertEquals(legacy.knownTopics(),json.treeToValue(json.valueToTree(legacy),Input.class).knownTopics());
    }
}
