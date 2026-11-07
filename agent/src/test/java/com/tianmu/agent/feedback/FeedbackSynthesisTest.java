package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackSynthesisTest {
    final ObjectMapper json=new ObjectMapper();
    int bytes(Object value){try{return json.writeValueAsBytes(value).length;}catch(Exception e){throw new AssertionError(e);}}
    Topic topic(int i,String observation){return new Topic("batch"+i+":T1","观察"+i,observation,"保留相反偏好，不预先判定同一立场。",List.of("G"+i),i+1,i%2);}
    @Test void smallObservationsFitTogetherWithoutDeletingProseWeightsOrReferences(){
        var topics=new ArrayList<Topic>();for(int i=0;i<27;i++)topics.add(topic(i,"有人支持原声，也有人希望轻配乐。"));
        var inputs=FeedbackSynthesis.batches(topics,this::bytes);assertEquals(1,inputs.size());assertEquals(topics,inputs.get(0).children());
        var material=FeedbackSynthesis.material(topics);assertEquals(27,material.references().size());
        assertEquals("batch26:T1",material.references().get("C27"));
        var payload=json.valueToTree(material.payload());assertFalse(payload.path("children").get(0).has("members"));
        assertEquals(topics.get(26).observation(),payload.path("children").get(26).path("observation").asText());
    }
    @Test void utf8AndJsonEscapingDetermineBudgetAndEveryChildAppearsExactlyOnce(){
        var topics=new ArrayList<Topic>();for(int i=0;i<70;i++)topics.add(topic(i,"原声与轻配乐偏好相反；\"保留原话\"。".repeat(45)));
        var inputs=FeedbackSynthesis.batches(topics,this::bytes);assertTrue(inputs.size()>2);
        assertEquals(topics,inputs.stream().flatMap(i->i.children().stream()).toList());
        for(var input:inputs){assertTrue(input.children().size()<=FeedbackSynthesis.MAX_CHILDREN);assertTrue(bytes(FeedbackSynthesis.material(input.children()).payload())<=FeedbackSynthesis.MAX_PAYLOAD_BYTES);}
    }
    @Test void boundedChildCountStillAppliesToTinyObservations(){
        var topics=new ArrayList<Topic>();for(int i=0;i<70;i++)topics.add(topic(i,"短句"));
        assertEquals(List.of(32,32,6),FeedbackSynthesis.batches(topics,this::bytes).stream().map(i->i.children().size()).toList());
    }
    @Test void nonReducibleEscapedInputsFailBeforePlanningAnEndlessUnaryChain(){
        var huge=new Topic("batch:T1","标题","\u0000".repeat(2400),"\u0000".repeat(1200),List.of("G1"),1,0);
        var other=new Topic("other:T1",huge.title(),huge.observation(),huge.recommendation(),List.of("G2"),1,0);
        assertEquals(1,FeedbackSynthesis.batches(List.of(huge),this::bytes).size());
        assertEquals("FULL_ANALYSIS_TOO_LARGE",assertThrows(AgentException.class,()->FeedbackSynthesis.batches(List.of(huge,other),this::bytes)).code());
    }
}
