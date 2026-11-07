package com.tianmu.agent.harness;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ClarificationToolTest {
    @Test void generalToolEndsTheTurnWithoutActivatingSkillsOrCallingBusinessTools(){
        try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();var calls=new AtomicInteger();
            f.execute(run,c->{assertEquals(1,calls.incrementAndGet());assertTrue(c.activeSkills().isEmpty());
                assertTrue(c.availableTools().stream().anyMatch(t->t.name().equals("ask_clarification")));
                return Decision.tool("ask_clarification",Map.of("question","你希望关注哪些反馈？"));});
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status());assertEquals(StopReason.CLARIFICATION,run.snapshot().stopReason());
            assertEquals("你希望关注哪些反馈？",run.snapshot().clarification());assertNull(run.snapshot().answer());assertEquals(0,f.queries.get());
            assertEquals("ask_clarification",run.execution().exchanges().get(0).call().name());
            assertEquals("你希望关注哪些反馈？",run.execution().observations().get(0).terminalClarification());
        }
    }
    @Test void clarificationCannotShareAReadBatch(){
        try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();f.execute(run,c->new Decision(Kind.TOOL,new ToolCall("ask_clarification",Map.of("question","哪一个？")),null,null,
                    List.of(new ToolCall("list_skills",Map.of()))));
            assertEquals(Status.FAILED,run.snapshot().status());assertEquals("INVALID_DECISION",run.snapshot().errorCode());
            assertNull(run.snapshot().clarification());assertTrue(run.execution().exchanges().isEmpty());
        }
    }
    @Test void malformedQuestionIsCorrectedBeforeAnyClarificationIsDelivered(){
        try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();f.execute(run,c->{
                if(c.observations().isEmpty())return Decision.tool("ask_clarification",Map.of("question",1));
                assertEquals("INVALID_ARGUMENTS",c.observations().get(0).data().get("error_code"));
                return Decision.tool("ask_clarification",Map.of("question","请补充希望的分析目标。"));
            });
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status());assertEquals(2,run.snapshot().modelCalls());assertEquals(0,f.queries.get());
        }
    }
    @Test void internalClarifyDecisionUsesTheSameToolAndHostDelivery(){
        try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();f.execute(run,c->Decision.clarify("需要补充范围。"));
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status());
            assertEquals("ask_clarification",run.execution().exchanges().get(0).call().name());
        }
    }
    @Test void hooksCannotReplaceTheHostClarificationOutcome(){
        var hook=TransformHookTest.hook("tamper-question",com.tianmu.agent.harness.hook.TransformPoint.TOOL_RESULT,0,
                (input,context)->new ToolResult(input.tool(),input.data(),null,null,null,"被替换的问题"));
        try(var f=new HarnessLifecycleTest.Fixture(List.of(hook))){
            var run=f.run();f.execute(run,c->Decision.tool("ask_clarification",Map.of("question","原始问题？")));
            assertEquals(Status.FAILED,run.snapshot().status());assertEquals("HOOK_TRANSFORM_INVALID",run.snapshot().errorCode());
            assertNull(run.snapshot().clarification());
        }
    }
    @Test void newOutcomesRoundTripAndHistoricalToolResultsRemainReadable()throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var result=new ToolResult("ask_clarification",Map.of(),null,null,null,"请补充目标。");
        assertEquals(result,json.readValue(json.writeValueAsBytes(result),ToolResult.class));
        assertNull(json.readValue("{\"tool\":\"list_skills\",\"data\":{},\"evidenceId\":null,\"terminalAnswer\":null,\"pending\":null}",ToolResult.class).terminalClarification());
    }
}
