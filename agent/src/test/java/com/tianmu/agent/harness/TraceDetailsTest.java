package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceDetailsTest {
    @Test void selectedToolsIncludeEveryCallAndExcludeArguments() {
        var first = new com.tianmu.agent.core.AgentTypes.ToolCall("analyze_danmaku", Map.of("private", "fixture"));
        var second = new com.tianmu.agent.core.AgentTypes.ToolCall("analyze_comments", Map.of());
        var decision = new com.tianmu.agent.core.AgentTypes.Decision(com.tianmu.agent.core.AgentTypes.Kind.TOOL, first, null, null, List.of(second));
        var attributes = TraceAttributes.sanitize(TraceSemantics.result(decision));
        assertEquals("analyze_danmaku", attributes.get("model.selected_tool"));
        assertEquals(List.of("analyze_danmaku", "analyze_comments"), attributes.get("model.selected_tools"));
        assertFalse(attributes.toString().contains("fixture"));
    }
    @Test void cacheMissAndMissingUsageSurviveExportWithoutImplyingCompleteCacheAccounting(){
        var usage=new TraceUsage();
        try(var scope=Context.current().with(TraceUsage.KEY,usage).makeCurrent()){
            TraceUsage.record("fixture",100L,10L,60L,null,null);TraceUsage.record("fixture",100L,10L,null,40L,null);
            TraceUsage.record("fixture",null,null,null,null,null);
        }
        var attrs=TraceAttributes.sanitize(usage.totals());assertEquals(40L,attrs.get("usage.cache_miss_tokens"));
        assertEquals(0L,attrs.get("usage.cache_reported_calls"));assertEquals(3L,attrs.get("usage.attempted_calls"));
        assertEquals(1L,attrs.get("usage.unreported_calls"));assertEquals(false,attrs.get("usage.complete"));assertEquals("RUN_PROCESS_SEGMENT",attrs.get("usage.scope"));
    }
    @Test void nestedDatabaseOperationsAreTimedOnceAndFailuresRemainFailures(){
        var recording=new HarnessLifecycleTest.Recording();var io=new IoTimings();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build()){
            var span=provider.get("test").spanBuilder("feedback.work").startSpan();
            try(var scope=Context.current().with(IoTimings.KEY,io).makeCurrent()){
                assertThrows(IllegalStateException.class,()->IoTimings.measure(IoTimings.Kind.FEEDBACK_DATABASE,()->
                        IoTimings.measure(IoTimings.Kind.FEEDBACK_DATABASE,()->{throw new IllegalStateException("fixture");})));
                IoTimings.measure(IoTimings.Kind.FEEDBACK_OBJECT,()->42);
            }
            io.export(span);span.end();var attrs=TraceEnvelope.from(recording.spans.get(0),"test","v1").attributes();
            assertEquals(1L,attrs.get("io.feedback_database_calls"));assertEquals(1L,attrs.get("io.feedback_object_calls"));assertTrue(((Number)attrs.get("io.feedback_database_ms")).doubleValue()>=0);
        }
    }
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @Test void sourceGuardRetainsRedactedBodiesForEveryDirectionIncludingTruncatedPreviews()throws Exception{
        var recording=new HarnessLifecycleTest.Recording();var details=new TraceDetails(new FileObjectStore(directory),json,4096,true);var run=UUID.randomUUID();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build()){
            var span=provider.get("test").spanBuilder("agent.model").startSpan();
            try(var scope=details.bind(Context.root().with(span),run).makeCurrent()){
                TraceDetails.protectMemory();
                for(String direction:List.of("input","output","provider_output","error","reasoning","prompt","code","execution"))
                    TraceDetails.capture(direction,Map.of("body","ACTUAL_BODY "+"文".repeat(direction.equals("output")?10000:1),"password","private-password"),"TEST");
            }finally{span.end();}
            var attrs=recording.spans.get(0).getAttributes();
            for(String direction:List.of("input","output","provider_output","error","reasoning","prompt","code","execution")){
                String[] ref=attrs.get(AttributeKey.stringKey("detail."+direction+"_ref")).split("/");
                byte[] payload=details.read(run,ref[0],ref[1],Long.parseLong(ref[2]),ref[3]);
                assertTrue(details.requiresSourceGuard(payload),direction);
                String body=new String(payload,java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(body.contains("ACTUAL_BODY"),direction);assertFalse(body.contains("private-password"));assertFalse(body.contains("memoryProtected"));
                assertEquals("SOURCE_GUARDED",attrs.get(AttributeKey.stringKey("detail."+direction+"_access")));
            }
        }
    }
    @Test void providerJsonTextRetainsWhitespaceAndMalformedTrailingBracesForDiagnosis() throws Exception {
        var recording=new HarnessLifecycleTest.Recording();
        var details=new TraceDetails(new FileObjectStore(directory),json,4096,true);UUID run=UUID.randomUUID();
        String valid="  { \"kind\" : \"TOOL\" }  ";String malformed="{\"kind\":\"TOOL\"}}";
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build()) {
            var span=provider.get("test").spanBuilder("agent.model").startSpan();
            try(var scope=details.bind(Context.root().with(span),run).makeCurrent()) {
                TraceDetails.capture("provider_output",Map.of("valid",valid,"malformed",malformed),"MODEL_PROVIDER_RESPONSE");
            } finally {span.end();}
            String[] ref=recording.spans.get(0).getAttributes().get(AttributeKey.stringKey("detail.provider_output_ref")).split("/");
            var value=json.readTree(details.read(run,ref[0],ref[1],Long.parseLong(ref[2]),ref[3])).path("value");
            assertEquals(valid,value.path("valid").asText());assertEquals(malformed,value.path("malformed").asText());
        }
    }
    @Test void privateSnapshotsRedactNestedCredentialsAndMarkTruncationWithoutExportingBodies() throws Exception {
        var recording = new HarnessLifecycleTest.Recording();
        var details = new TraceDetails(new FileObjectStore(directory), json, 4096, true);
        UUID run = UUID.randomUUID();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build()) {
            var span = provider.get("test").spanBuilder("agent.model").startSpan();
            try (var scope = details.bind(Context.root().with(span), run).makeCurrent()) {
                TraceNode.begin(span, "agent.model");
                TraceDetails.input(Map.of("apiKey", "secret-key", "messages", List.of(Map.of("content", "{\"scope\":{\"authorizationRef\":\"scope-secret\"},\"email\":\"person@example.com\",\"phone\":\"13800138000\"}"))));
                TraceDetails.output(Map.of("large", "文本".repeat(10000)));
                TraceNode.success(span);
            } finally { span.end(); }
            var data = recording.spans.get(0);
            String inputRef = data.getAttributes().get(AttributeKey.stringKey("detail.input_ref"));
            String[] parts = inputRef.split("/");
            String snapshot = new String(details.read(run, parts[0], parts[1], Long.parseLong(parts[2]), parts[3]), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(snapshot.contains("secret-key")); assertFalse(snapshot.contains("scope-secret"));
            assertFalse(snapshot.contains("person@example.com")); assertFalse(snapshot.contains("13800138000"));
            assertTrue(json.readTree(snapshot).path("redacted").asBoolean());
            assertEquals("TRUNCATED", data.getAttributes().get(AttributeKey.stringKey("detail.output_state")));
            assertThrows(AgentException.class, () -> details.read(UUID.randomUUID(), parts[0], parts[1], Long.parseLong(parts[2]), parts[3]));
            assertFalse(TraceEnvelope.from(data, "test", "v1").toString().contains("authorizationRef"));
        }
    }
    @Test void unknownUsageIsAbsentAndKnownOptionalUsageAndTimeoutStateAreExplicit() {
        var recording = new HarnessLifecycleTest.Recording();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build()) {
            var span = provider.get("test").spanBuilder("agent.model").startSpan();
            TraceUsage usage = new TraceUsage();
            assertFalse(usage.totals().containsKey("usage.input_tokens"));
            try (var scope = Context.root().with(span).with(TraceUsage.KEY, usage).makeCurrent()) {
                TraceNode.begin(span, "agent.model");
                TraceUsage.record("mock", 12L, 4L, 8L, 4L, 2L);
                TraceNode.failure(span, new AgentException("MODEL_CALL_TIMEOUT", "Call exceeded time budget"));
            } finally { span.end(); }
            var attributes = recording.spans.get(0).getAttributes();
            assertEquals("TIMED_OUT", attributes.get(AttributeKey.stringKey("node.status")));
            assertEquals(16L, attributes.get(AttributeKey.longKey("model.total_tokens")));
            assertEquals(8L, usage.totals().get("usage.cache_hit_tokens"));
            assertEquals(2L, usage.totals().get("usage.reasoning_tokens"));
        }
    }
}
