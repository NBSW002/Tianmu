package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.model.*;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class InputValidationRecoveryTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    ToolDispatcher dispatcher(HarnessLifecycleTest.Fixture fixture, AtomicInteger effects, Function<ToolCall,ToolResult> action) {
        var handlers=new CatalogArchitectureTest().builtins();handlers.removeIf(t->t.name().equals("read_result"));
        handlers.add(new AgentTool() {
            public String name(){return "read_result";}
            public Exposure exposure(){return Exposure.GENERAL;}
            public boolean batchable(){return true;}
            public ToolResult execute(ToolCall call,ToolContext context){effects.incrementAndGet();return action.apply(call);}
        });
        var skills=new SkillCatalog();return new ToolDispatcher(skills,fixture.harness,new ToolRegistry(handlers,new ToolCatalog(),skills));
    }
    Decision call(String name,Map<String,Object> arguments,String id) {
        return new Decision(Kind.TOOL,new ToolCall(name,arguments,id),null,null);
    }
    ToolResult empty(ToolCall call){return new ToolResult(call.name(),Map.of(),null);}

    @Test void rejectedDateReachesActualHttpAsPairedFeedbackThenOneCorrectedQuerySucceeds() throws Exception {
        var requests=new CopyOnWriteArrayList<JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","fixture"))),
                    "usage",Map.of("prompt_tokens",1,"completion_tokens",1)));
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var run=fixture.run();run.execution().activateSkill("video-metrics");var count=new AtomicInteger();
            var client=new DeepSeekClient(new DeepSeekProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-only","fixture","fixture",Duration.ofSeconds(2),384,1536),json);
            ModelPort model=new ModelPort() {
                public Decision decide(ModelContext context){throw new AssertionError("Use controlled path");}
                public Decision decide(ModelContext context,CallControl control) {
                    client.completeHarness("fixture",client.harnessMessages("rules",context),context.availableTools(),128,control);
                    int step=count.getAndIncrement();
                    if(step==0)return call("query_video_metrics",Map.of("video_id",1001,"date","SECRET_BAD_DATE","metric","bullet_count"),"bad-call");
                    if(step==1) {
                        assertEquals(0,fixture.queries.get());var feedback=context.observations().get(0);
                        assertEquals("INVALID_ARGUMENTS",feedback.data().get("error_code"));assertNull(feedback.evidenceId());assertNull(feedback.terminalAnswer());
                        var value=json.valueToTree(feedback.data()).path("input_validation");
                        assertFalse(value.path("handler_executed").asBoolean());assertEquals("date",value.path("issues").get(0).path("path").asText());
                        assertEquals("DATE",value.path("issues").get(0).path("code").asText());assertFalse(feedback.data().toString().contains("SECRET_BAD_DATE"));
                        return call("query_video_metrics",Map.of("video_id",1001,"date",run.businessDate.minusDays(1).toString(),"metric","bullet_count"),"corrected-call");
                    }
                    return Decision.answer("128",List.of(context.observations().get(1).evidenceId()));
                }
            };
            fixture.execute(run,model);assertEquals(Status.SUCCEEDED,run.snapshot().status());assertEquals(1,fixture.queries.get());assertEquals(3,requests.size());
            var messages=requests.get(1).path("messages");var reply=messages.get(messages.size()-1);
            assertEquals("bad-call",reply.path("tool_call_id").asText());assertEquals("bad-call",messages.get(messages.size()-2).path("tool_calls").get(0).path("id").asText());
            assertEquals("BEFORE_HANDLER",json.readTree(reply.path("content").asText()).path("data").path("input_validation").path("phase").asText());
            var spans=fixture.recording.spans.stream().filter(s->s.getName().equals("agent.tool")).toList();assertEquals(2,spans.size());
            assertEquals(false,spans.get(0).getAttributes().get(AttributeKey.booleanKey("tool.handler_executed")));
            assertEquals(true,spans.get(0).getAttributes().get(AttributeKey.booleanKey("operation.executed")));
            assertEquals(false,spans.get(0).getAttributes().get(AttributeKey.booleanKey("tool.success")));
            assertEquals(true,spans.get(1).getAttributes().get(AttributeKey.booleanKey("tool.handler_executed")));
        } finally {server.stop(0);}
    }
    @Test void switchingToolsAndAnInterveningSuccessCannotResetTheRunAllowance() {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var effects=new AtomicInteger();var tools=dispatcher(fixture,effects,this::empty);var run=fixture.run();var count=new AtomicInteger();
            new AgentRuntime(context->switch(count.getAndIncrement()) {
                case 0 -> Decision.tool("read_result",Map.of());
                case 1 -> Decision.tool("read_history",Map.of("before","not-an-integer"));
                case 2 -> Decision.tool("list_skills",Map.of());
                default -> Decision.tool("read_result",Map.of());
            },tools,fixture.harness).execute(run);
            assertEquals("TOOL_FAILURE_LIMIT",run.snapshot().errorCode());assertEquals(4,count.get());assertEquals(0,effects.get());
            assertEquals(3,run.execution().observations().size());assertEquals(2,run.execution().observations().stream().filter(r->r.data().containsKey("input_validation")).count());
        }
    }
    @ParameterizedTest @ValueSource(strings={"code","helper","exception","reserved"})
    void handlerFailuresAndFabricatedMetadataNeverGrantCorrection(String mode) {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var effects=new AtomicInteger();var tools=dispatcher(fixture,effects,call->{
                if(mode.equals("reserved"))return new ToolResult(call.name(),Map.of("input_validation",Map.of("handler_executed",false)),null);
                if(mode.equals("helper"))ToolArguments.validate(new ToolCall(call.name(),Map.of()),new ToolCatalog().all().get(call.name()));
                if(mode.equals("exception"))throw new IllegalArgumentException("SECRET_HANDLER_FAILURE");
                throw new AgentException("INVALID_ARGUMENTS","SECRET_HANDLER_FAILURE");
            });var count=new AtomicInteger();var run=fixture.run();
            new AgentRuntime(context->{count.incrementAndGet();return Decision.tool("read_result",Map.of("result_id","ev_any"));},tools,fixture.harness).execute(run);
            assertEquals(mode.equals("reserved")?"INVALID_TOOL_RESULT":"INVALID_ARGUMENTS",run.snapshot().errorCode());
            assertEquals(1,effects.get());assertEquals(1,count.get());assertTrue(run.execution().observations().isEmpty());
        }
    }
    @Test void legacyExecuteKeepsFailFastAdmissionAndNeverCallsTheHandler() {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var effects=new AtomicInteger();var tools=dispatcher(fixture,effects,this::empty);var run=fixture.run();run.start();
            assertInstanceOf(ToolDispatcher.ValidationRejected.class,tools.dispatch(new ToolCall("read_result",Map.of()),run));
            assertEquals("INVALID_ARGUMENTS",assertThrows(AgentException.class,()->tools.execute(new ToolCall("read_result",Map.of()),run)).code());
            assertEquals(0,effects.get());run.cancel();
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void rawAndTransformedBatchErrorsKeepFailFastWithoutPartialRecovery(boolean transform) {
        var rewrite=TransformHookTest.hook("drop-second-id",TransformPoint.TOOL_CALL,0,(input,context)->
                "second".equals(input.arguments().get("result_id"))?new ToolCall(input.name(),Map.of(),input.providerCallId(),input.providerGroupId()):input);
        try(var fixture=new HarnessLifecycleTest.Fixture(transform?List.of(rewrite):List.of())) {
            var effects=new AtomicInteger();var tools=dispatcher(fixture,effects,this::empty);var count=new AtomicInteger();var run=fixture.run();
            new AgentRuntime(context->{count.incrementAndGet();return new Decision(Kind.TOOL,
                    new ToolCall("read_result",transform?Map.of("result_id","first"):Map.of(),"p1","group"),null,null,
                    List.of(new ToolCall("read_result",Map.of("result_id","second"),"p2","group")));},tools,fixture.harness).execute(run);
            assertEquals("INVALID_ARGUMENTS",run.snapshot().errorCode());assertEquals(1,count.get());assertEquals(transform?1:0,effects.get());
            assertTrue(run.execution().observations().stream().noneMatch(r->r.data().containsKey("input_validation")));
        }
    }
    @ParameterizedTest @ValueSource(strings={"remove","alter","inject"})
    void resultHooksCannotRemoveAlterOrInjectHostValidationFacts(String mode) {
        var tamper=TransformHookTest.hook("tamper-input-validation",TransformPoint.TOOL_RESULT,0,(input,context)->{
            var data=new HashMap<>(input.data());
            if(mode.equals("remove"))data.remove("input_validation");else data.put("input_validation",Map.of("handler_executed",true));
            return new ToolResult(input.tool(),data,input.evidenceId());
        });
        try(var fixture=new HarnessLifecycleTest.Fixture(List.of(tamper))) {
            var effects=new AtomicInteger();var tools=dispatcher(fixture,effects,this::empty);var run=fixture.run();var count=new AtomicInteger();
            new AgentRuntime(context->{count.incrementAndGet();return Decision.tool("read_result",mode.equals("inject")?Map.of("result_id","ev_any"):Map.of());},tools,fixture.harness).execute(run);
            assertEquals("HOOK_TRANSFORM_INVALID",run.snapshot().errorCode());assertEquals(1,count.get());assertEquals(mode.equals("inject")?1:0,effects.get());
        }
    }
    @Test void transformedArgumentsRatherThanOriginalArgumentsAreValidated() {
        var rewrite=TransformHookTest.hook("bad-result-id",TransformPoint.TOOL_CALL,0,(input,context)->input.name().equals("read_result")?new ToolCall(input.name(),Map.of("result_id",42),input.providerCallId()):input);
        try(var fixture=new HarnessLifecycleTest.Fixture(List.of(rewrite))) {
            var effects=new AtomicInteger();var tools=dispatcher(fixture,effects,this::empty);var count=new AtomicInteger();var run=fixture.run();
            new AgentRuntime(context->count.getAndIncrement()==0?Decision.tool("read_result",Map.of("result_id","ev_original")):Decision.clarify("Need a valid result"),tools,fixture.harness).execute(run);
            assertEquals(Status.NEEDS_INPUT,run.snapshot().status());assertEquals(0,effects.get());
            assertEquals(42,run.execution().exchanges().get(0).call().arguments().get("result_id"));
        }
    }
    @Test void stricterExistingFailureLimitStopsBeforeAnyCorrectionFeedback() {
        var policy=new LoopPolicy(6,Duration.ofSeconds(5),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofMillis(100),1,false);
        try(var fixture=new LoopControlTest.Fixture(policy)) {
            var run=new RunState(new AccessScope("t","u",Set.of(1001L)),"query");var count=new AtomicInteger();
            new AgentRuntime(context->{count.incrementAndGet();return Decision.tool("read_skill",Map.of("name",42));},fixture.tools,fixture.harness).execute(run);
            assertEquals("TOOL_FAILURE_LIMIT",run.snapshot().errorCode());assertEquals(1,count.get());assertTrue(run.execution().observations().isEmpty());
        }
    }
    @Test void cancellationWinsBeforeValidationCanProduceFeedback() {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var run=fixture.run();var effects=new AtomicInteger();var tools=dispatcher(fixture,effects,this::empty);
            new AgentRuntime(context->{run.cancel();return Decision.tool("read_result",Map.of());},tools,fixture.harness).execute(run);
            assertEquals(Status.CANCELLED,run.snapshot().status());assertEquals(0,effects.get());assertTrue(run.execution().observations().isEmpty());
        }
    }
    @ParameterizedTest @ValueSource(strings={"steps","calls"})
    void argumentCorrectionCannotExtendExistingStepOrModelCallBudgets(String limit) {
        var policy=new LoopPolicy(limit.equals("steps")?1:6,Duration.ofSeconds(5),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofMillis(100),3,false,limit.equals("calls")?1:16);
        try(var fixture=new LoopControlTest.Fixture(policy)) {
            var run=new RunState(new AccessScope("t","u",Set.of(1001L)),"query");var count=new AtomicInteger();
            new AgentRuntime(context->{count.incrementAndGet();return Decision.tool("read_skill",Map.of("name",42));},fixture.tools,fixture.harness).execute(run);
            assertEquals(limit.equals("steps")?"STEP_LIMIT":"MODEL_CALL_BUDGET_EXCEEDED",run.snapshot().errorCode());
            assertEquals(1,count.get());assertEquals(1,run.execution().observations().size());assertTrue(run.execution().activeSkills().isEmpty());
        }
    }
}
