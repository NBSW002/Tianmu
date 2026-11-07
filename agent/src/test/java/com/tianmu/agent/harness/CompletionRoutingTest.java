package com.tianmu.agent.harness;

import com.tianmu.agent.application.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.AgentRuntime;
import com.tianmu.agent.runtime.spi.CompletionEvaluator;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CompletionRoutingTest {
    AgentRuntime runtime(HarnessLifecycleTest.Fixture fixture,ModelPort model,CompletionEvaluator evaluator){
        return new AgentRuntime(model,fixture.tools,fixture.harness,new PromptCatalog(),new DeliveryService(fixture.harness),new BusinessTaskExecutionPolicy(),evaluator);
    }
    @Test void modelAnswerIsAProposalAndTheOwnerCanRequireClarification(){
        try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();var proposals=new AtomicInteger();
            runtime(f,c->Decision.answer("unapproved",List.of()),input->{
                if(input.answerProposal()==null)return new CompletionEvaluator.Continue("WAIT_FOR_PROPOSAL");
                proposals.incrementAndGet();return new CompletionEvaluator.Clarify("owner question");
            }).execute(run);
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status());
            assertEquals("owner question",run.snapshot().clarification());assertNull(run.snapshot().answer());assertEquals(1,proposals.get());
        }
    }
    @Test void toolClarificationCannotBypassTheCompletionOwner(){
        try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();
            runtime(f,c->Decision.clarify("unapproved"),input->input.clarificationProposal()==null
                    ?new CompletionEvaluator.Continue("NO_PROPOSAL"):new CompletionEvaluator.Reject("OWNER_REJECTED")).execute(run);
            assertEquals(Status.FAILED,run.snapshot().status());assertEquals("OWNER_REJECTED",run.snapshot().errorCode());assertNull(run.snapshot().clarification());
        }
    }
    @Test void aSerialNativeBatchIsEvaluatedOnlyAfterBothResultsHaveSettled(){
        try(var f=new HarnessLifecycleTest.Fixture()){
            var run=f.run();run.execution().activateSkill("video-metrics");var settled=new AtomicInteger();
            var yesterday=run.businessDate.minusDays(1).toString();var before=run.businessDate.minusDays(2).toString();
            var a=new ToolCall("query_video_metrics",Map.of("video_id",1001,"date",yesterday,"metric","bullet_count"),"batch-a","batch-group","");
            var b=new ToolCall("query_video_metrics",Map.of("video_id",1001,"date",before,"metric","bullet_count"),"batch-b","batch-group","");
            runtime(f,c->new Decision(Kind.TOOL,a,null,null,List.of(b)),input->{
                if(input.exchanges().isEmpty())return new CompletionEvaluator.Continue("NO_RESULTS");
                assertEquals(2,input.exchanges().size(),"A partial batch must not reach the owner");settled.incrementAndGet();
                return new CompletionEvaluator.Clarify("batch settled");
            }).execute(run);
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status());assertEquals(1,settled.get());assertEquals(2,run.execution().exchanges().size());
        }
    }
    @Test void duplicateTaskOwnersAreRejectedInsteadOfDependingOnRegistrationOrder(){
        var owner=new BusinessCompletionEvaluator.GeneralOwner();
        assertThrows(IllegalArgumentException.class,()->new BusinessCompletionEvaluator(List.of(owner,owner)));
    }
}
