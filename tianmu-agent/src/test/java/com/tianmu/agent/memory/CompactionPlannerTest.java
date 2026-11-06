package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompactionPlannerTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CompactionPlanner planner=new CompactionPlanner(json);
    List<MemoryTypes.Message> messages(){var result=new ArrayList<MemoryTypes.Message>();for(int i=0;i<4;i++){var run=UUID.randomUUID();for(int j=0;j<2;j++)result.add(new MemoryTypes.Message(UUID.randomUUID(),run,i*2+j+1,j==0?"user":"assistant","讨论方案以及被否决的设计原因。".repeat(40),Instant.EPOCH));}return result;}
    @Test void cutsOnlyWholeTurnsWithinTheGenerationBudget(){var input=messages();int firstTwo=planner.bytes(input.subList(0,4));var plan=planner.plan(input,512,firstTwo+10);assertEquals(4,plan.messages().size());assertEquals(1,plan.from());assertEquals(4,plan.through());assertNull(planner.plan(input.subList(0,2),512,32768));}
    @Test void doesNotTreatPartialTurnsAsACompleteSourceRange(){var input=new ArrayList<>(messages());input.remove(1);assertNull(planner.plan(input,512,32768));}
    @Test void rejectsFabricatedReferencesAndExpandingSummaries()throws Exception{
        var plan=planner.plan(messages(),512,32768);var node=json.readTree("{\"synopsis\":\"继续方案讨论\",\"claims\":[{\"type\":\"rejected_option\",\"text\":\"用户否决旧设计\",\"source_refs\":[\""+plan.messages().get(0).id()+"\"]}],\"pending_work\":[\"完善设计\"]}");
        assertEquals("继续方案讨论",planner.validate(node,plan,3500).get("synopsis"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)node.path("claims").get(0).path("source_refs")).set(0,json.getNodeFactory().textNode(UUID.randomUUID().toString()));assertThrows(AgentException.class,()->planner.validate(node,plan,3500));
    }
}
