package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepSeekFeedbackAssignmentsTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final DeepSeekClient client=mock(DeepSeekClient.class);
    Input input(){return new Input(WorkKind.CLASSIFY,List.of(new Group("G1",Source.COMMENT,"first","",false,null,1,List.of("C:1")),
            new Group("G2",Source.COMMENT,"second","",false,null,1,List.of("C:2"))),List.of(),null);}
    List<Proposal> analyze(String assignments,Input input)throws Exception {
        when(client.messages(anyString(),anyMap())).thenReturn(List.of(Map.of("role","user","content","fixture")));
        var response=json.readTree("{\"topics\":[{\"key\":\"T1\",\"title\":\"topic\",\"observation\":\"observed\",\"recommendation\":\"\"}],\"assignments\":"+assignments+"}");
        when(client.complete(anyString(),anyList(),eq(6144),any(CallControl.class),eq(true))).thenReturn(response);
        var config=new DeepSeekProperties(URI.create("http://localhost"),"fixture","fixture","fixture",Duration.ofSeconds(1),384,3072);
        return new DeepSeekFeedbackModels(client,config,json,new PromptCatalog()).analyze(input,new CallControl(Instant.now().plusSeconds(30),new CancellationToken()));
    }
    @Test void hostBuildsDisjointMembersFromOneTopicKeyPerInputId()throws Exception {
        var proposals=analyze("{\"G1\":\"T1\",\"G2\":\"T1\"}",input());
        assertEquals(List.of("G1","G2"),proposals.get(0).members());
        var objects=new FeedbackObjects(mock(com.tianmu.agent.runtime.store.ObjectStore.class),json);
        var result=new FeedbackPlanner(objects,FeedbackPolicy.defaults()).validate("work",input(),proposals);
        assertEquals(2,result.covered());
    }
    @Test void missingAndUnknownIdsRemainRejectedInsteadOfAutoClassified()throws Exception {
        var objects=new FeedbackObjects(mock(com.tianmu.agent.runtime.store.ObjectStore.class),json);var planner=new FeedbackPlanner(objects,FeedbackPolicy.defaults());
        for(String assignments:List.of("{\"G1\":\"T1\"}","{\"G1\":\"T1\",\"G2\":\"T1\",\"G3\":\"T1\"}")) {
            var proposed=analyze(assignments,input());assertEquals("INVALID_ANALYSIS",assertThrows(AgentException.class,()->planner.validate("work",input(),proposed)).code());
        }
    }
    @Test void multipleTopicValuesAndUnknownTopicKeysAreInvalid() {
        for(String assignments:List.of("{\"G1\":[\"T1\",\"T2\"]}","{\"G1\":\"T99\"}"))
            assertEquals("INVALID_ANALYSIS",assertThrows(AgentException.class,()->analyze(assignments,input())).code());
    }
    @Test void correctionCarriesAssignmentsAndMissingIdsWithoutRepeatingRejectedProse()throws Exception {
        var base=input();var correction=new Correction(List.of(new Proposal("T1","topic","observed","",List.of("G1"))),List.of("G2"),List.of(),List.of());
        analyze("{\"G1\":\"T1\",\"G2\":\"T1\"}",new Input(base.kind(),base.groups(),base.children(),null,correction));
        var capture=org.mockito.ArgumentCaptor.forClass(Map.class);verify(client).messages(anyString(),capture.capture());
        var payload=json.valueToTree(capture.getValue());assertEquals("G2",payload.path("correction").path("missing").get(0).asText());
        assertEquals("T1",payload.path("correction").path("previous_assignments").path("G1").asText());
        assertFalse(payload.path("correction").has("previous_topics"));
        assertEquals(0,payload.path("correction").path("unknown_count").asInt());
        assertFalse(payload.toString().contains("originalIds"));
    }
}
