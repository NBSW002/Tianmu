package com.tianmu.agent.harness;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.RunState;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparationTraceStateTest {
    private PreparationPolicy policy() { return new PreparationPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(200), Duration.ofSeconds(1), true, false); }
    private SpanData span(HarnessLifecycleTest.Fixture f, String name) {
        var matches = f.recording.spans.stream().filter(s -> s.getName().equals("agent.prepare." + name)).toList();
        assertEquals(1, matches.size(), name); return matches.get(0);
    }
    private String attr(SpanData span, String key) { return span.getAttributes().get(AttributeKey.stringKey(key)); }
    private Result prepare(HarnessLifecycleTest.Fixture f, PreparationService prep, RunState run) {
        var result = new java.util.concurrent.atomic.AtomicReference<Result>();
        f.harness.execute(run, () -> result.set(prep.prepare(run, run.message)));
        return result.get();
    }
    @Test void ruleRouteHasActualIntentSpanAndExplicitDisabledEmotionWithoutModelCalls() {
        try (var f = new HarnessLifecycleTest.Fixture()) {
            var model = mock(PreparationModelPort.class);
            try (var prep = new PreparationService((i,c) -> Optional.empty(), model, policy(), f.harness, List.of(new MetricQueryReadiness()))) {
                var run = new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "查询视频1001昨天的弹幕数");
                var result = prepare(f, prep, run);
                assertEquals(Route.HARNESS, result.route());
                assertFalse(result.request().skippedStages().contains("intent"));
                assertEquals("RULE", attr(span(f,"intent"), "preparation.method"));
                assertEquals("ROUTING", attr(span(f,"intent"), "node.type"));
                assertEquals("VIDEO_METRIC", attr(span(f,"intent"), "preparation.intent"));
                assertEquals(Boolean.FALSE, span(f,"validate").getAttributes().get(AttributeKey.booleanKey("clarification.required")));
                var emotion = span(f,"emotion");
                assertEquals("DISABLED", attr(emotion, "node.status"));
                assertEquals("CONFIG_DISABLED", attr(emotion, "preparation.reason"));
                assertEquals(Boolean.FALSE, emotion.getAttributes().get(AttributeKey.booleanKey("operation.executed")));
                assertEquals(0, run.snapshot().preparationCalls()); verifyNoInteractions(model); run.cancel();
            }
        }
    }
    @Test void clarificationRecordsTrueAndSkipsIntentInsteadOfInventingRecognition() {
        try (var f = new HarnessLifecycleTest.Fixture()) {
            var model = mock(PreparationModelPort.class);
            try (var prep = new PreparationService((i,c) -> Optional.empty(), model, policy(), f.harness,
                    List.of(input -> Optional.of(new Clarification(true, "PRIVATE_QUESTION"))))) {
                var run = f.run();
                var result = prepare(f, prep, run);
                assertEquals(Route.CLARIFY, result.route());
                assertEquals(Boolean.TRUE, span(f,"validate").getAttributes().get(AttributeKey.booleanKey("clarification.required")));
                assertNull(span(f,"validate").getAttributes().get(AttributeKey.booleanKey("faq.hit")));
                assertEquals("SKIPPED", attr(span(f,"intent"), "node.status"));
                assertEquals("CLARIFICATION_REQUIRED", attr(span(f,"intent"), "preparation.reason"));
                assertFalse(f.recording.spans.toString().contains("PRIVATE_QUESTION"));
                verifyNoInteractions(model); run.cancel();
            }
        }
    }
    @Test void faqHitRecordsWhyDownstreamChecksDidNotExecute() {
        try (var f = new HarnessLifecycleTest.Fixture()) {
            var model = mock(PreparationModelPort.class);
            try (var prep = new PreparationService((i,c) -> Optional.of(new FaqAnswer("help", "v1", "PRIVATE_FAQ")), model, policy(), f.harness)) {
                var run = f.run();
                assertEquals(Route.FAQ, prepare(f, prep, run).route());
                assertEquals(Boolean.TRUE, span(f,"faq").getAttributes().get(AttributeKey.booleanKey("faq.hit")));
                for (String name : List.of("intent", "validate")) {
                    assertEquals("SKIPPED", attr(span(f,name), "node.status"));
                    assertEquals("FAQ_HIT", attr(span(f,name), "preparation.reason"));
                }
                assertNull(span(f,"validate").getAttributes().get(AttributeKey.booleanKey("clarification.required")));
                assertEquals(0, run.snapshot().preparationCalls()); verifyNoInteractions(model); run.cancel();
            }
        }
    }
}
