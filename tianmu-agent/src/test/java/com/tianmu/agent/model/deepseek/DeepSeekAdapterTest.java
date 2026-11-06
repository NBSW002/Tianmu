package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.StopReason;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekAdapterTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    HttpServer server;
    DeepSeekProperties properties;
    DeepSeekClient client;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path detailDirectory;
    Function<JsonNode, String> answer = input -> "{\"kind\":\"CLARIFY\",\"clarification\":\"Date?\"}";
    Function<JsonNode,Map<String,Object>> nativeAnswer;
    int status = 200;
    CountDownLatch entered, release;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes()); requests.add(request);
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (entered != null) entered.countDown();
            if (release != null) try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            var message=nativeAnswer==null ? Map.<String,Object>of("content",answer.apply(request)):nativeAnswer.apply(request);
            byte[] body = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", message.containsKey("tool_calls")?"tool_calls":"stop",
                    "message", message)), "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)));
            try { exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); }
            finally { exchange.close(); }
        });
        server.start();
        properties = new DeepSeekProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-secret",
                "deepseek-flash", "deepseek-v4-pro", Duration.ofMillis(300), 384, 1536);
        client = new DeepSeekClient(properties, mapper);
    }
    @AfterEach void stop() { if (release != null) release.countDown(); server.stop(0); }
    CallControl control() { return new CallControl(Instant.now().plusSeconds(2), new CancellationToken()); }
    Input input() { return new Input("视频1001昨天的弹幕数", LocalDate.of(2026, 10, 21), new AccessScope("private-tenant", "private-user", Set.of(1001L))); }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void revokedContextBarrierPreventsAnyActualHttpDispatch(boolean streaming){
        var rejected=control().withDispatchBarrier(()->{throw new AgentException("MEMORY_CONTEXT_INVALIDATED","revoked");});
        var messages=client.messages("rules",Map.of("query","fabricated private source"));
        var error=assertThrows(AgentException.class,()->{if(streaming)client.completeHarness("deepseek-flash",messages,List.of(),128,rejected.withTextOutput(text->{}));else client.complete("deepseek-flash",messages,128,rejected);});
        assertEquals("MEMORY_CONTEXT_INVALIDATED",error.code());assertTrue(requests.isEmpty());
    }
    Input candidateInput() {
        var old=input();var catalog=new com.tianmu.agent.preparation.IntentCandidateCatalog(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.tool.ToolCatalog());
        return new Input(old.query(),old.businessDate(),old.scope(),old.memory(),old.requestedAt(),catalog.candidates(old.scope()));
    }
    @Test void capabilityIntentReceivesTheSnapshotAndReturnsBoundedPrimaryAndRelatedGoals() throws Exception {
        answer=request->"{\"primary\":\"audience-report\",\"related\":[\"video-content\",\"comment-analysis\"]}";
        var model=new DeepSeekPreparationModel(client,properties,mapper,new PromptCatalog());
        var result=model.recognizeIntent(candidateInput(),control());
        assertEquals(Intent.DANMAKU_REPORT,result.intent());assertEquals("audience-report",result.primary());
        assertEquals(List.of("video-content","comment-analysis"),result.related());
        var messages=requests.get(0).path("messages");var payload=mapper.readTree(messages.get(messages.size()-1).path("content").asText());
        assertEquals(12,payload.path("intentCandidates").size());
        assertEquals("视频1001昨天的弹幕数",payload.path("query").asText());
    }
    @Test void capabilityIntentRejectsUnregisteredPrimaryAndInventedRelatedGoals() {
        var model=new DeepSeekPreparationModel(client,properties,mapper,new PromptCatalog());
        for(String response:List.of("{\"primary\":\"run_shell\",\"related\":[]}","{\"primary\":\"knowledge\",\"related\":[\"not-available\"]}","{\"primary\":\"unknown\",\"related\":[\"video-content\"]}")) {
            answer=request->response;assertThrows(IllegalArgumentException.class,()->model.recognizeIntent(candidateInput(),control()));
        }
    }
    ModelContext context() { return new ModelContext(input().query(), input().businessDate(), input().scope(), Set.of(), List.of(), List.of(), 6); }
    ModelContext nativeContext(List<ToolResult> results,List<ToolExchange> exchanges) {
        return new ModelContext(input().query(),input().businessDate(),input().scope(),Set.of(),results,
                List.of(new ToolDefinition("analyze_video_content","video",Map.of("type","object","properties",Map.of("video_id",Map.of("type","integer")))),new ToolDefinition("query_audience","audience",Map.of("type","object"))),6,false,"",null,null,com.tianmu.agent.core.MemoryTypes.View.empty(),exchanges);
    }
    @Test void nativeToolDefinitionsAndActualProviderIdSurviveTheNextTurn() {
        nativeAnswer=request->Map.of("tool_calls",List.of(Map.of("id","provider-call-731","type","function","function",Map.of("name","analyze_video_content","arguments","{\"video_id\":2114367144786530304,\"focus\":\"观众反馈中提到的画面\"}"))));
        var model=new DeepSeekHarnessModel(client,properties,mapper,new PromptCatalog());
        var decision=model.decide(nativeContext(List.of(),List.of()),control());
        assertEquals(2114367144786530304L,((Number)decision.call().arguments().get("video_id")).longValue());
        assertEquals("provider-call-731",decision.call().providerCallId());
        assertEquals("analyze_video_content",requests.get(0).path("tools").get(0).path("function").path("name").asText());
        assertFalse(requests.get(0).has("response_format"));assertTrue(requests.get(0).path("parallel_tool_calls").asBoolean());
        nativeAnswer=null;answer=request->"已读取，这是基于画面的建议。";
        var result=new ToolResult(decision.call().name(),Map.of("summary","鸭子"),"ev_1");
        var finalDecision=model.decide(nativeContext(List.of(result),List.of(new ToolExchange(decision.call(),result))),control());
        assertEquals("已读取，这是基于画面的建议。",finalDecision.answer().text());assertEquals(List.of("ev_1"),finalDecision.answer().evidenceIds());
        var messages=requests.get(1).path("messages");
        assertEquals("provider-call-731",messages.get(messages.size()-2).path("tool_calls").get(0).path("id").asText());
        assertEquals("provider-call-731",messages.get(messages.size()-1).path("tool_call_id").asText());
        assertTrue(messages.get(messages.size()-2).path("content").asText().isEmpty());
        assertNotEquals(new ToolCall("x",Map.of("a",1),"first"),new ToolCall("x",Map.of("a",1),"second"));
    }
    @Test void parameterDescriptionsAndDynamicNamesReachTheActualNativeRequestUnchanged() {
        var catalog=new com.tianmu.agent.tool.ToolCatalog();var skills=new com.tianmu.agent.skill.SkillCatalog();
        var declared=catalog.all().values().stream().map(t->t.name().equals("read_skill")?
                new com.tianmu.agent.tool.builtin.ReadSkillTool().describe(t,skills):t).toList();
        client.completeHarness(properties.harnessModel(),List.of(Map.of("role","user","content","Inspect available tools")),declared,128,control());
        var functions=new HashMap<String,JsonNode>();
        for(var tool:requests.get(0).path("tools"))functions.put(tool.path("function").path("name").asText(),tool.path("function"));
        for(var definition:declared)assertEquals(mapper.valueToTree(definition.inputSchema()),functions.get(definition.name()).path("parameters"));
        var name=functions.get("read_skill").path("parameters").path("properties").path("name");
        assertFalse(name.path("description").asText().isBlank());assertEquals(mapper.valueToTree(skills.names().stream().sorted().toList()),name.path("enum"));
        assertFalse(functions.get("prepare_creator_interactions").path("parameters").path("properties").path("items").path("items").path("properties").path("playback_ms").path("description").asText().isBlank());
    }
    @Test void independentFeedbackSourcesEnableNativeParallelCallsOnlyWhenBothAreAvailable(){
        var tools=List.of(new ToolDefinition("analyze_comments","comments",Map.of("type","object")),
                new ToolDefinition("analyze_danmaku","danmaku",Map.of("type","object")));
        client.completeHarness(properties.harnessModel(),List.of(Map.of("role","user","content","prepare")),tools,128,control());
        assertTrue(requests.get(0).path("parallel_tool_calls").asBoolean());
        client.completeHarness(properties.harnessModel(),List.of(Map.of("role","user","content","prepare")),tools.subList(0,1),128,control());
        assertFalse(requests.get(1).path("parallel_tool_calls").asBoolean());
    }
    @Test void requiredResourceReadsReachTheProviderAsRequiredAndReleaseAfterTheRead(){
        var tools=nativeContext(List.of(),List.of()).availableTools();
        nativeAnswer=request->Map.of("tool_calls",List.of(Map.of("id","required-read","type","function","function",Map.of("name","analyze_video_content","arguments","{\"video_id\":1001}"))));
        client.completeHarness(properties.harnessModel(),List.of(Map.of("role","user","content","read")),tools,128,control(),true);
        assertEquals("required",requests.get(0).path("tool_choice").asText());assertEquals("disabled",requests.get(0).path("thinking").path("type").asText());
        nativeAnswer=null;answer=request->"已读取。";
        client.completeHarness(properties.harnessModel(),List.of(Map.of("role","user","content","answer")),tools,128,control(),false);
        assertEquals("auto",requests.get(1).path("tool_choice").asText());
        assertEquals("INVALID_DECISION",assertThrows(AgentException.class,()->client.completeHarness(properties.harnessModel(),List.of(Map.of("role","user","content","read")),tools,128,control(),true)).code());
    }
    @Test void namedResourceStepsAreForcedAndCannotSelectUnavailableOrDifferentTools(){
        var tools=nativeContext(List.of(),List.of()).availableTools();
        nativeAnswer=request->Map.of("tool_calls",List.of(Map.of("id","named-step","type","function","function",Map.of("name","analyze_video_content","arguments","{\"video_id\":1001}"))));
        client.completeHarness(properties.harnessModel(),List.of(Map.of("role","user","content","read")),tools,128,control(),"analyze_video_content");
        assertEquals("analyze_video_content",requests.get(0).path("tool_choice").path("function").path("name").asText());
        assertEquals("INVALID_DECISION",assertThrows(AgentException.class,()->client.completeHarness(properties.harnessModel(),List.of(Map.of("role","user","content","read")),tools,128,control(),"unknown_tool")).code());
        assertEquals("INVALID_DECISION",assertThrows(AgentException.class,()->client.completeHarness(properties.harnessModel(),List.of(Map.of("role","user","content","read")),tools,128,control(),"query_audience")).code());
    }
    @Test void parameterDescriptionsStillCountTowardTheWholeRequestLimitBeforeHttpAndReservation() throws Exception {
        var definition=new com.tianmu.agent.tool.ToolCatalog().all().get("query_video_metrics");
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(definition);
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("inputSchema").path("properties").path("date")).put("description","中文".repeat(256));
        var admitted=new com.tianmu.agent.tool.ToolCatalog(new org.springframework.core.io.Resource[]{new org.springframework.core.io.ByteArrayResource(mapper.writeValueAsBytes(body))}).all().get(definition.name());
        var bounded=new DeepSeekClient(properties,mapper,new com.tianmu.agent.model.ContextPolicy(1024,0));var budget=new ModelBudget(10000,50);
        assertEquals("MODEL_CONTEXT_TOO_LARGE",assertThrows(AgentException.class,()->bounded.completeHarness(properties.harnessModel(),
                List.of(Map.of("role","user","content","q")),List.of(admitted),128,control().withBudget(budget))).code());
        assertTrue(requests.isEmpty());assertEquals(0L,budget.attributes().get("budget.charged_tokens"));
    }
    @Test void redundantRootClosingBracesAreRepairedWithoutAnotherProviderCall() {
        answer=request->"{\"kind\":\"CLARIFY\",\"clarification\":\"Date?\"}}}";
        assertEquals("Date?",client.complete(properties.preparationModel(),List.of(Map.of("role","user","content","JSON")),384,control()).path("clarification").asText());
        assertEquals(1,requests.size());
    }
    @Test void independentNativeCallsRemainOneAssistantMessageWithBothResults() {
        nativeAnswer=request->Map.of("tool_calls",List.of(
                Map.of("id","p1","type","function","function",Map.of("name","query_audience","arguments","{\"video_id\":1001}")),
                Map.of("id","p2","type","function","function",Map.of("name","analyze_video_content","arguments","{\"video_id\":1001}"))));
        var model=new DeepSeekHarnessModel(client,properties,mapper,new PromptCatalog());var d=model.decide(nativeContext(List.of(),List.of()),control());
        assertEquals(1,d.additionalCalls().size());assertEquals("p1",d.call().providerGroupId());assertEquals("p1",d.additionalCalls().get(0).providerGroupId());
        var first=new ToolResult("query_audience",Map.of("count",243),"ev_a");var second=new ToolResult("analyze_video_content",Map.of("summary","鸭子"),"ev_v");
        nativeAnswer=null;answer=request->"结合画面的建议。";
        var reply=model.decide(nativeContext(List.of(first,second),List.of(new ToolExchange(d.call(),first),new ToolExchange(d.additionalCalls().get(0),second))),control());
        assertEquals(List.of("ev_a","ev_v"),reply.answer().evidenceIds());
        var m=requests.get(1).path("messages");assertEquals(2,m.get(m.size()-3).path("tool_calls").size());
        assertEquals("p1",m.get(m.size()-2).path("tool_call_id").asText());assertEquals("p2",m.get(m.size()-1).path("tool_call_id").asText());
    }
    @Test void equalNativeResultsKeepBothOriginalGroupsAndIdsInTheActualHttpRequest() {
        var shared=new ToolResult("query_audience",Map.of("count",0),null);
        var exchanges=new ArrayList<ToolExchange>();
        for(int i=1;i<=4;i++)exchanges.add(new ToolExchange(new ToolCall(shared.tool(),Map.of("video_id",1001,"page",i),"p"+i,i<=2?"g1":"g2"),shared));
        var model=new DeepSeekHarnessModel(client,properties,mapper,new PromptCatalog());
        model.decide(nativeContext(Collections.nCopies(4,shared),exchanges),control());
        var messages=requests.get(0).path("messages");List<String> calls=new ArrayList<>(),replies=new ArrayList<>();List<Integer> groups=new ArrayList<>();
        for(var message:messages) {
            if(message.has("tool_calls")) {groups.add(message.path("tool_calls").size());for(var call:message.path("tool_calls"))calls.add(call.path("id").asText());}
            if(message.path("role").asText().equals("tool"))replies.add(message.path("tool_call_id").asText());
        }
        assertEquals(List.of(2,2),groups);assertEquals(List.of("p1","p2","p3","p4"),calls);assertEquals(calls,replies);
    }
    @Test void brokenNativeRecordsStopBeforeHttpDispatchOrTokenReservation() {
        var shared=new ToolResult("query_audience",Map.of("count",0),null);
        var exchange=new ToolExchange(new ToolCall(shared.tool(),Map.of(),"p1"),shared);
        var budget=new ModelBudget(10000,50);var model=new DeepSeekHarnessModel(client,properties,mapper,new PromptCatalog());
        assertEquals("INVALID_MODEL_CONTEXT",assertThrows(AgentException.class,()->model.decide(
                nativeContext(List.of(shared,shared),List.of(exchange)),control().withBudget(budget))).code());
        assertTrue(requests.isEmpty());assertEquals(0L,budget.attributes().get("budget.charged_tokens"));
    }
    @Test void jsonToolDecisionsAndDsmlBodiesAreNeverExecutedAsNativeCalls() {
        var model=new DeepSeekHarnessModel(client,properties,mapper,new PromptCatalog());
        for(String invalid:List.of("{\"kind\":\"TOOL\",\"call\":{\"name\":\"analyze_video_content\",\"arguments\":{\"video_id\":1001}}}","{\"kind\":\"CLARIFY\",\"clarification\":\"x\"}<｜｜DSML｜｜tool_calls>")) {
            answer=request->invalid;assertThrows(RuntimeException.class,()->model.decide(nativeContext(List.of(),List.of()),control()));
        }
        nativeAnswer=request->Map.of("tool_calls",List.of(Map.of("id","p1","type","function","function",Map.of("name","unregistered","arguments","{}"))));
        assertEquals("INVALID_DECISION",assertThrows(AgentException.class,()->model.decide(nativeContext(List.of(),List.of()),control())).code());
    }
    @Test void repairNeverAcceptsDuplicateKeysOrTrailingData() {
        var model=new DeepSeekHarnessModel(client,properties,mapper,new PromptCatalog());
        for(String invalid:List.of("{\"kind\":\"CLARIFY\",\"kind\":\"ANSWER\",\"clarification\":\"Date?\"}}", "{\"kind\":\"CLARIFY\",\"clarification\":\"Date?\"} {}")) {
            answer=request->invalid;
            assertThrows(RuntimeException.class,()->model.decide(context(),control()));
        }
    }
    @Test void springBindsContextLimitsIntoSharedClient() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("deepseek"))
                .withUserConfiguration(DeepSeekAdapters.class)
                .withBean(ObjectMapper.class, () -> mapper).withBean(PromptCatalog.class, PromptCatalog::new)
                .withBean(io.opentelemetry.api.trace.Tracer.class,()->io.opentelemetry.api.OpenTelemetry.noop().getTracer("binding-test"))
                .withPropertyValues("agent.models.api-key=test-secret", "agent.context.max-input-bytes=1024", "agent.context.max-history-bytes=0")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var error = assertThrows(AgentException.class, () -> context.getBean(DeepSeekClient.class).messages("s".repeat(2000), Map.of("query", "q")));
                    assertEquals("MODEL_CONTEXT_TOO_LARGE", error.code());
                });
    }
    @Test void oversizedDirectMessagesAreRejectedBeforeNetworkAndTokenReservation() {
        var bounded = new DeepSeekClient(properties, mapper, new com.tianmu.agent.model.ContextPolicy(1024, 0));
        var budget = new ModelBudget(100, 50);
        try (var scope = io.opentelemetry.context.Context.current().makeCurrent()) {
            var error = assertThrows(AgentException.class, () -> bounded.complete(properties.harnessModel(),
                    List.of(Map.of("role", "user", "content", "x".repeat(2000))), 128, control().withBudget(budget)));
            assertEquals("MODEL_CONTEXT_TOO_LARGE", error.code());
            assertEquals(0L, budget.attributes().get("budget.charged_tokens"));
            assertTrue(requests.isEmpty());
        }
    }
    @Test void requestSpansCountDispatchedCallsAndKeepFailureLatencyAndPayloadComposition(){
        var spans=new CopyOnWriteArrayList<io.opentelemetry.sdk.trace.data.SpanData>();
        var exporter=new io.opentelemetry.sdk.trace.export.SpanExporter(){
            public io.opentelemetry.sdk.common.CompletableResultCode export(Collection<io.opentelemetry.sdk.trace.data.SpanData> batch){spans.addAll(batch);return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();}
            public io.opentelemetry.sdk.common.CompletableResultCode flush(){return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();}
            public io.opentelemetry.sdk.common.CompletableResultCode shutdown(){return flush();}
        };
        try(var provider=io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()){
            var traced=new DeepSeekClient(properties,mapper,new com.tianmu.agent.model.ContextPolicy(2048,0),provider.get("test"));
            var usage=new com.tianmu.agent.harness.trace.TraceUsage();
            try(var scope=io.opentelemetry.context.Context.current().with(com.tianmu.agent.harness.trace.TraceUsage.KEY,usage)
                    .with(com.tianmu.agent.harness.trace.TraceUsage.RUN_ID,"fixture-run").makeCurrent()){
                traced.complete(properties.harnessModel(),List.of(Map.of("role","user","content","fixture")),128,control());
                status=429;assertThrows(RuntimeException.class,()->traced.complete(properties.harnessModel(),List.of(Map.of("role","user","content","fixture")),128,control()));
                assertThrows(AgentException.class,()->traced.complete(properties.harnessModel(),List.of(Map.of("role","user","content","x".repeat(3000))),128,control()));
            }
            var calls=spans.stream().filter(s->s.getName().equals("model.request")).map(s->com.tianmu.agent.harness.trace.TraceEnvelope.from(s,"test","v1").attributes()).toList();
            assertEquals(3,calls.size());assertEquals(List.of(true,true,false),calls.stream().map(a->a.get("model.dispatched")).toList());
            for(int i=0;i<2;i++){assertTrue(((Number)calls.get(i).get("model.response_ms")).longValue()>=0);assertTrue(((Number)calls.get(i).get("context.request_bytes")).longValue()>0);assertEquals("fixture-run",calls.get(i).get("run.id"));}
            assertEquals(429L,calls.get(1).get("model.http_status"));assertEquals("NOT_REPORTED",calls.get(1).get("model.usage_state"));
            assertEquals(2L,usage.totals().get("usage.attempted_calls"));assertEquals(false,usage.totals().get("usage.complete"));assertEquals(2,requests.size());
        }
    }
    @Test void inputAwareTokenLimitStopsNetworkDispatch() {
        var budget=new ModelBudget(500,50);
        var error=assertThrows(AgentException.class,()->client.complete(properties.harnessModel(),
                List.of(Map.of("role","user","content","JSON "+"中文".repeat(200))),128,control().withBudget(budget)));
        assertEquals("MODEL_TOKEN_BUDGET_EXCEEDED",error.code());assertTrue(requests.isEmpty());
        assertEquals(0L,budget.attributes().get("budget.charged_tokens"));
    }
    @Test void quotaRejectionPausesSubsequentCallsBeforeNetworkAndBudgetReservation() {
        status=402;var budget=new ModelBudget(10000,50);
        var error=assertThrows(AgentException.class,()->client.complete(properties.harnessModel(),List.of(Map.of("role","user","content","JSON")),128,control().withBudget(budget)));
        assertEquals("MODEL_QUOTA_EXHAUSTED",error.code());assertEquals(1,requests.size());
        long charged=(long)budget.attributes().get("budget.charged_tokens");status=200;
        assertEquals("MODEL_QUOTA_EXHAUSTED",assertThrows(AgentException.class,()->client.complete(properties.harnessModel(),List.of(Map.of("role","user","content","JSON")),128,control().withBudget(budget))).code());
        assertEquals(1,requests.size());assertEquals(charged,budget.attributes().get("budget.charged_tokens"));
    }
    @Test void bothPreparationAndHarnessSendCompactedHistoryWithoutMutatingSource() throws Exception {
        var bounded = new DeepSeekClient(properties, mapper, new com.tianmu.agent.model.ContextPolicy(98304, 256));
        var memory = new com.tianmu.agent.core.MemoryTypes.View(1, "old".repeat(2000), List.of(), false, "untrusted");
        var adapters = new DeepSeekAdapters();
        answer = request -> request.path("model").asText().equals(properties.preparationModel())
                ? "{\"emotion\":\"NEUTRAL\"}" : "{\"kind\":\"CLARIFY\",\"clarification\":\"Which date?\"}";
        adapters.deepSeekPreparation(bounded, properties, mapper, new PromptCatalog()).emotion(
                new Input(input().query(), input().businessDate(), input().scope(), memory), control());
        adapters.deepSeekHarnessModel(bounded, properties, mapper, new PromptCatalog()).decide(
                new ModelContext(input().query(), input().businessDate(), input().scope(), Set.of(), List.of(), List.of(), 6,
                        false, "", null, null, memory), control());
        assertEquals(2, requests.size());
        JsonNode payload=mapper.readTree(requests.get(0).path("messages").get(1).path("content").asText());
        assertEquals(input().query(),payload.path("query").asText());
        assertTrue(payload.path("memory").path("truncated").asBoolean());
        assertEquals("",payload.path("memory").path("olderSummary").asText());
        var nativeMessages=requests.get(1).path("messages");
        assertEquals("user",nativeMessages.get(nativeMessages.size()-1).path("role").asText());
        assertEquals(input().query(),nativeMessages.get(nativeMessages.size()-1).path("content").asText());
        assertFalse(nativeMessages.toString().contains("oldoldold"));
        assertFalse(memory.truncated()); assertEquals(6000, memory.olderSummary().length());
    }
    @Test void modelTraceCapturesActualMessagesProviderResponseAndReportedUsageWithoutCredentials() throws Exception {
        var exported = new CopyOnWriteArrayList<io.opentelemetry.sdk.trace.data.SpanData>();
        var exporter = new io.opentelemetry.sdk.trace.export.SpanExporter() {
            public io.opentelemetry.sdk.common.CompletableResultCode export(Collection<io.opentelemetry.sdk.trace.data.SpanData> spans) { exported.addAll(spans); return flush(); }
            public io.opentelemetry.sdk.common.CompletableResultCode flush() { return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess(); }
            public io.opentelemetry.sdk.common.CompletableResultCode shutdown() { return flush(); }
        };
        var details = new com.tianmu.agent.harness.trace.TraceDetails(new com.tianmu.agent.adapters.storage.FileObjectStore(detailDirectory), mapper, 131072, true);
        UUID run = UUID.randomUUID();
        try (var provider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()) {
            var span = provider.get("test").spanBuilder("agent.model").startSpan();
            try (var scope = details.bind(io.opentelemetry.context.Context.root().with(span), run).makeCurrent()) {
                new DeepSeekAdapters().deepSeekHarnessModel(client, properties, mapper, new PromptCatalog()).decide(context(), control());
            } finally { span.end(); }
            var attributes = exported.get(0).getAttributes();
            assertEquals(15L, attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("model.total_tokens")));
            assertEquals("stop", attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("model.finish_reason")));
            assertEquals("NOT_APPLICABLE", attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("model.first_token_state")));
            for (String direction : List.of("input", "provider_output")) {
                String[] ref = attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("detail." + direction + "_ref")).split("/");
                String body = new String(details.read(run, ref[0], ref[1], Long.parseLong(ref[2]), ref[3]), StandardCharsets.UTF_8);
                assertFalse(body.contains("test-secret"));
                assertEquals(direction.equals("input") ? "MODEL_REQUEST" : "MODEL_PROVIDER_RESPONSE", mapper.readTree(body).path("kind").asText());
            }
        }
    }
    @Test void oneKeySelectsTwoModelsAndDoesNotSendIdentityScope() {
        answer = request -> request.path("model").asText().equals("deepseek-flash") ? "{\"emotion\":\"NEUTRAL\"}"
                : "{\"kind\":\"CLARIFY\",\"clarification\":\"Date?\"}";
        var adapters = new DeepSeekAdapters();
        assertEquals(Emotion.NEUTRAL, adapters.deepSeekPreparation(client, properties, mapper, new PromptCatalog()).emotion(input(), control()));
        assertEquals(Kind.CLARIFY, adapters.deepSeekHarnessModel(client, properties, mapper, new PromptCatalog()).decide(context(), control()).kind());
        assertEquals(List.of("Bearer test-secret", "Bearer test-secret"), auth);
        assertEquals("deepseek-flash", requests.get(0).path("model").asText());
        assertEquals("deepseek-v4-pro", requests.get(1).path("model").asText());
        assertEquals("json_object",requests.get(0).path("response_format").path("type").asText());
        assertFalse(requests.get(1).has("response_format"));
        for (JsonNode request : requests) {
            assertEquals("disabled", request.path("thinking").path("type").asText());
            assertFalse(request.toString().contains("private-tenant")); assertFalse(request.toString().contains("private-user"));
        }
        assertFalse(properties.toString().contains("test-secret"));
    }
    String understood(String query) {
        return "{\"needsClarification\":false,\"clarificationQuestion\":null,\"conditions\":{\"video_id\":1001,\"date\":\"2026-10-20\",\"metric\":\"bullet_count\"},\"rewrittenQuery\":\"" + query + "\"}";
    }
    @Test void singleUnderstandingCallParsesClarificationConditionsAndRewrite() {
        var port = new DeepSeekAdapters().deepSeekPreparation(client, properties, mapper, new PromptCatalog());
        answer = request -> understood(input().query());
        Understanding result = port.understand(input(), control());
        assertFalse(result.needsClarification());
        assertEquals(input().query(), result.rewrittenQuery());
        assertEquals(1001, result.conditions().get("video_id"));
        assertEquals(1, requests.size());
        assertFalse(requests.get(0).toString().contains("private-user"));
        answer = request -> "{\"needsClarification\":true,\"clarificationQuestion\":\"Which date?\",\"conditions\":{},\"rewrittenQuery\":null}";
        assertEquals("Which date?", port.understand(input(), control()).clarificationQuestion());
    }
    @Test void understandingReusesLongOriginalWhenRewriteIsNull() {
        var port = new DeepSeekAdapters().deepSeekPreparation(client, properties, mapper, new PromptCatalog());
        String original = "请概括学习笔记：" + "先理解缓存，再理解索引，最后理解事务。".repeat(60);
        var longInput = new Input(original, input().businessDate(), input().scope());
        answer = request -> "{\"needsClarification\":false,\"clarificationQuestion\":null,\"conditions\":{},\"rewrittenQuery\":null}";
        Understanding result = port.understand(longInput, control());
        assertFalse(result.needsClarification());
        assertEquals(original, result.rewrittenQuery());
        assertTrue(result.conditions().isEmpty());
        assertEquals(1, requests.size());
    }
    @Test void understandingRejectsCoercionExtraFieldsAndContradictoryPayloads() {
        var port = new DeepSeekAdapters().deepSeekPreparation(client, properties, mapper, new PromptCatalog());
        for (String invalid : List.of(
                understood("query").replace("\"needsClarification\":false", "\"needsClarification\":\"false\""),
                understood("query").replace("\"video_id\":1001", "\"video_id\":{\"nested\":1001}"),
                understood("query").replace("\"clarificationQuestion\":null", "\"clarificationQuestion\":\"Which date?\""),
                understood("query").replace("\"needsClarification\":false", "\"needsClarification\":true")
                        .replace("\"clarificationQuestion\":null", "\"clarificationQuestion\":\"Which date?\""),
                understood("query").replace("\"rewrittenQuery\":\"query\"", "\"rewrittenQuery\":7"),
                understood("query").replace("\"conditions\":{", "\"extra\":true,\"conditions\":{"))) {
            answer = request -> invalid;
            assertThrows(IllegalArgumentException.class, () -> port.understand(input(), control()));
        }
    }
    @Test void intentUsesIndependentStructuredModelRequestAndRejectsUnknownFields() {
        var port = new DeepSeekAdapters().deepSeekPreparation(client, properties, mapper, new PromptCatalog());
        answer = request -> "{\"intent\":\"VIDEO_METRIC\"}";
        assertEquals(Intent.VIDEO_METRIC, port.intent(input(), control()));
        assertEquals(properties.preparationModel(), requests.get(0).path("model").asText());
        assertFalse(requests.get(0).toString().contains("private-tenant"));
        assertFalse(requests.get(0).toString().contains("private-user"));
        for (String invalid : List.of("{\"intent\":\"ADMIN\"}", "{\"intent\":\"OTHER\",\"extra\":true}", "{\"intent\":7}")) {
            answer = request -> invalid;
            assertThrows(IllegalArgumentException.class, () -> port.intent(input(), control()));
        }
    }

    @Test void persistedPreparationWithoutIntentRemainsReadable() throws Exception {
        PreparedRequest old = mapper.readValue("""
                {"originalQuery":"q","query":"q","emotion":"UNKNOWN","degradedStages":[],"skippedStages":[]}
                """, PreparedRequest.class);
        assertEquals(Intent.UNKNOWN, old.intent());
        assertEquals("q", old.query());
    }

    @Test void invalidDecisionAndProviderAuthFailureAreDistinctAndSanitized() {
        var port = new DeepSeekAdapters().deepSeekHarnessModel(client, properties, mapper, new PromptCatalog());
        answer = request -> "{\"kind\":\"ANSWER\",\"answer\":{\"text\":\"unsupported\",\"evidenceIds\":[3]}}";
        assertEquals("INVALID_DECISION", assertThrows(AgentException.class, () -> port.decide(context(), control())).code());
        status = 401; answer = request -> "PROVIDER_SECRET_DETAIL";
        AgentException error = assertThrows(AgentException.class, () -> port.decide(context(), control()));
        assertEquals("MODEL_AUTH_FAILED", error.code()); assertFalse(error.getMessage().contains("PROVIDER_SECRET_DETAIL"));
    }
    @Test void oversizedAndDuplicateKeyResponsesAreRejected() {
        answer = request -> "x".repeat(140000);
        assertThrows(AgentException.class, () -> client.complete(properties.harnessModel(), List.of(Map.of("role", "user", "content", "JSON")), 128, control()));
        answer = request -> "{\"query\":\"a\",\"query\":\"b\"}";
        assertEquals("MODEL_ERROR",assertThrows(AgentException.class, () -> client.complete(properties.harnessModel(), List.of(Map.of("role", "user", "content", "JSON")), 128, control())).code());
    }
    @Test void transientAndMalformedResponsesExposeRetryableCodesWithoutClientSideRetry(){
        status=429;
        assertEquals("MODEL_UNAVAILABLE",assertThrows(AgentException.class,()->client.complete(properties.harnessModel(),List.of(Map.of("role","user","content","JSON")),128,control())).code());
        status=200;answer=request->"{\"topics\":[],\"assignments\":{},\"}";
        assertEquals("MODEL_ERROR",assertThrows(AgentException.class,()->client.complete(properties.harnessModel(),List.of(Map.of("role","user","content","JSON")),128,control())).code());
        assertEquals(2,requests.size(),"Only the durable worker owns retry and backoff");
    }
    @Test void cancellationCancelsActiveHttpRequest() throws Exception {
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        CallControl control = control(); ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(() -> client.complete(properties.harnessModel(), List.of(Map.of("role", "user", "content", "JSON")), 128, control));
            assertTrue(entered.await(1, TimeUnit.SECONDS)); control.cancellation().cancel(StopReason.USER_CANCELLED);
            ExecutionException error = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
            assertInstanceOf(AgentException.class, error.getCause());
            assertEquals("CANCELLED", ((AgentException) error.getCause()).code());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
