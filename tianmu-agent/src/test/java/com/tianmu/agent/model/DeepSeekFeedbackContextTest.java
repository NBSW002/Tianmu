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
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));
            var result=Map.of("topics",List.of(Map.of("key","T1","title","反馈","observation","意见","recommendation","")),"assignments",Map.of("G1","T1"));
            byte[] response=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(result))))));
            exchange.sendResponseHeaders(200,response.length);try(var out=exchange.getResponseBody()){out.write(response);}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    DeepSeekFeedbackModels adapter(ContextPolicy policy){
        var config=new DeepSeekProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"local-test","fixture","fixture",Duration.ofSeconds(2),384,3072);
        return new DeepSeekFeedbackModels(new DeepSeekClient(config,json,policy),config,json,new PromptCatalog());
    }
    CallControl control(){return new CallControl(Instant.now().plusSeconds(15),new CancellationToken());}
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
}
