package com.tianmu.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.runtime.events.InMemoryRunEventStore;
import com.tianmu.agent.runtime.store.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Characterizes the approved migration baseline; deliberately does not simulate SQL restart recovery. */
class GovernanceBehaviorBaselineTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AccessScope owner = new AccessScope("governance", "alice", Set.of(1001L));

    private RunState run(Instant acceptedAt, Instant deadline, RunBackend backend) {
        return new RunState(UUID.randomUUID(), UUID.randomUUID(), 1, owner, "question", "question",
                acceptedAt, deadline, backend, new InMemoryRunEventStore(), new InMemoryEvidenceStore(), false);
    }

    private RunState running(int calls) {
        Instant now = Instant.now();
        var run = run(now, now.plusSeconds(120), RunBackend.MEMORY);
        run.bindPersistedControl(calls);
        assertTrue(run.start());
        return run;
    }

    private PendingOperation waitForAnalysis(RunState run) {
        var operation = new PendingOperation(UUID.randomUUID(), "feedback-analysis", run.deadline());
        run.execution().observe(new ToolCall("analyze_feedback", Map.of(), "provider-analysis"),
                new ToolResult("analyze_feedback", Map.of("status", "PENDING"), null, null, operation));
        run.waitFor(operation);
        return operation;
    }

    @Test void preparationLoopAndNestedCallsShareAdmissionWhileBackgroundOnlyChangesTheDisplayTotal() {
        var run = running(6);
        for (int i = 0; i < 3; i++) run.preparationCallStarted();
        run.modelCallStarted();
        run.externalModelCallStarted();
        run.backgroundModelCalls(200);
        run.backgroundModelCalls(200);
        run.backgroundModelCalls(199);
        run.modelCallStarted();

        assertEquals(3, run.snapshot().preparationCalls());
        assertEquals(2, run.snapshot().modelCalls());
        assertEquals(200, run.snapshot().backgroundModelCalls());
        assertEquals(206, run.snapshot().totalModelCalls());
        assertEquals("MODEL_CALL_BUDGET_EXCEEDED", assertThrows(AgentException.class,
                run::externalModelCallStarted).code());
        assertEquals(206, run.snapshot().totalModelCalls(), "Rejected dispatch cannot increment a counter");
        run.cancel();
    }

    @Test void waitingJsonPreservesUnknownTokenChargesAndSharedCallAdmission() throws Exception {
        var run = running(4);
        run.preparationCallStarted();
        run.modelCallStarted();
        ModelBudget.execute(run.callControl(), "fixture", true, () -> json.createObjectNode());
        waitForAnalysis(run);
        var snapshot = json.readValue(json.writeValueAsBytes(run.snapshot()), Snapshot.class);
        var continuation = json.readValue(json.writeValueAsBytes(run.continuation()), PendingOperation.Continuation.class);
        var restored = new RunState(run.id, run.sessionId, run.turnSeq, owner, run.message, run.query(),
                run.createdAt, run.deadline(), RunBackend.MEMORY, new InMemoryRunEventStore(), new InMemoryEvidenceStore(), true);
        restored.bindPersistedControl(4);
        restored.restore(snapshot);
        restored.restoreContinuation(continuation);

        assertEquals(8192, restored.modelBudget().snapshot().charged());
        assertEquals(1, restored.modelBudget().snapshot().unknown());
        assertEquals("provider-analysis", restored.execution().exchanges().get(0).call().providerCallId());
        assertTrue(restored.completeOperation(continuation.operation(),
                new ToolResult("analyze_feedback", Map.of("status", "READY"), "ev_ready")));
        restored.modelCallStarted();
        assertEquals("MODEL_CALL_BUDGET_EXCEEDED", assertThrows(AgentException.class,
                restored::preparationCallStarted).code());
        assertEquals(8192, restored.modelBudget().snapshot().charged(), "Completion cannot reset unknown usage");
        restored.cancel();
        run.cancel();
    }

    @Test void successfulCompletionPersistsRunningWithoutAContinuationBeforeAnyResumeDispatch() throws Exception {
        var persisted = new ArrayList<byte[]>();
        var run = run(Instant.now(), Instant.now().plusSeconds(120), new RunBackend() {
            @Override public void save(RunState current) {
                try {
                    var record = json.createObjectNode();
                    record.set("snapshot", json.valueToTree(current.snapshot()));
                    record.set("continuation", json.valueToTree(current.continuation()));
                    persisted.add(json.writeValueAsBytes(record));
                } catch (Exception e) { throw new AssertionError(e); }
            }
        });
        assertTrue(run.start());
        var operation = waitForAnalysis(run);
        var waiting = json.readTree(persisted.get(persisted.size() - 1));
        assertFalse(waiting.path("continuation").isNull());

        assertTrue(run.completeOperation(operation, new ToolResult("analyze_feedback", Map.of("status", "READY"), "ev_ready")));
        var bound = json.readTree(persisted.get(persisted.size() - 1));
        assertEquals("RUNNING", bound.path("snapshot").path("status").asText());
        assertTrue(bound.path("continuation").isNull());
        assertFalse(bound.path("snapshot").has("exchanges"), "The current Run snapshot is not an execution journal");
        assertEquals("ev_ready", run.execution().exchanges().get(0).result().evidenceId());
        assertTrue(run.events().after(0).events().stream().noneMatch(e -> e.type().equals("DONE")));
        run.cancel();
    }

    @Test void reportWindowExtendsFromAcceptanceOnlyAfterTheInitialWindowCheck() {
        Instant accepted = Instant.now().minusSeconds(30);
        var run = run(accepted, accepted.plusSeconds(120), RunBackend.MEMORY);
        run.start();
        run.prepared(new Result(Route.HARNESS, new PreparedRequest(run.message, run.query(), Emotion.UNKNOWN, Set.of()), null, null));
        run.executionWindow(Duration.ofHours(1));
        assertEquals(accepted.plusSeconds(3600), run.deadline());
        run.cancel();

        var expired = run(accepted, accepted.plusSeconds(1), RunBackend.MEMORY);
        expired.start();
        assertEquals("DEADLINE_EXCEEDED", assertThrows(AgentException.class,
                () -> expired.executionWindow(Duration.ofHours(1))).code());
        assertEquals(Status.FAILED, expired.snapshot().status());
        assertEquals("DEADLINE_EXCEEDED", expired.snapshot().errorCode());
        assertEquals(accepted.plusSeconds(1), expired.deadline());
    }

    @Test void taskAssemblyKeepsTheFeedbackWindowAndDerivedTurnLimitVisible() {
        Instant accepted = Instant.now().minusSeconds(30);
        var run = run(accepted, accepted.plusSeconds(120), RunBackend.MEMORY);
        run.bindPersistedControl(16);
        run.start();
        var spec = com.tianmu.agent.preparation.TaskMeaning.defaults().resolve(
                "只根据视频1001全部评论生成报告", run.businessDate, accepted, run.memory());
        run.prepared(new Result(Route.HARNESS, new PreparedRequest(run.message, run.query(), Emotion.UNKNOWN,
                Set.of(), Set.of(), Intent.DANMAKU_REPORT, spec, null), null, null));
        var assembler = new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),
                new com.tianmu.agent.prompt.PromptCatalog(), new com.tianmu.agent.tool.ToolCatalog());
        var task = assembler.assemble(run);
        assertEquals(accepted.plusSeconds(3600), task.deadline());
        assertEquals(15, com.tianmu.agent.application.AnalysisExecutionPolicy.maxTurns(spec, 6, task.maxModelCalls()));
        run.task(task);
        assertThrows(IllegalStateException.class, () -> run.executionWindow(Duration.ofHours(2)));
        assertEquals(task.deadline(), run.deadline());
        run.cancel();
    }

    @Test void reportReadyShortcutRequiresHostRegistrationAndCannotAcceptAPendingReply() {
        var run = running(16);
        var spec=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve("只根据视频1001全部评论生成报告",run.businessDate,run.createdAt,run.memory());
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.UNKNOWN,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec,null),null,null));
        run.task(new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog()).assemble(run));
        var call = new ToolCall("create_feedback_report", Map.of(), "report-call");
        var result = new ToolResult("create_feedback_report", Map.of("status", "READY", "url", "/report"), "ev_report");
        run.execution().observe(call, result);
        assertFalse((new com.tianmu.agent.application.BusinessCompletionEvaluator().evaluate(AgentRuntime.completionInput(run)) instanceof com.tianmu.agent.runtime.spi.CompletionEvaluator.Deliver), "A tool body cannot claim host registration");
        run.execution().reportReady("ev_report");
        assertTrue((new com.tianmu.agent.application.BusinessCompletionEvaluator().evaluate(AgentRuntime.completionInput(run)) instanceof com.tianmu.agent.runtime.spi.CompletionEvaluator.Deliver));
        var operation = new PendingOperation(UUID.randomUUID(), "feedback-report", run.deadline());
        assertThrows(IllegalArgumentException.class, () -> new ToolResult("create_feedback_report",
                Map.of("status", "READY", "url", "/report"), "ev_report", null, operation));
        run.execution().observe(new ToolCall("create_feedback_report", Map.of(), "pending-call"),
                new ToolResult("create_feedback_report", Map.of("status", "PENDING"), null, null, operation));
        assertFalse((new com.tianmu.agent.application.BusinessCompletionEvaluator().evaluate(AgentRuntime.completionInput(run)) instanceof com.tianmu.agent.runtime.spi.CompletionEvaluator.Deliver));
        assertEquals(Status.RUNNING, run.snapshot().status(), "Ready inspection does not commit delivery");
        run.cancel();
    }

    @Test void failedSuccessCommitDoesNotCreateAnAutomaticSuccessRetry() {
        var attempts = new AtomicInteger();
        Instant now = Instant.now();
        var run = run(now, now.plusSeconds(120), new RunBackend() {
            @Override public Snapshot commit(RunState current, Snapshot proposed, String event, Object data, Object done) {
                attempts.incrementAndGet();
                throw new AgentException("STORAGE_UNAVAILABLE", "Injected rollback");
            }
        });
        run.start();
        assertEquals("STORAGE_UNAVAILABLE", assertThrows(AgentException.class,
                () -> run.succeed(new Answer("draft", List.of()))).code());
        assertEquals(Status.RUNNING, run.snapshot().status());
        run.reconcileTerminal(Instant.now().plusSeconds(60));
        assertEquals(1, attempts.get());
        assertTrue(run.events().after(0).events().stream().noneMatch(e -> e.type().equals("ANSWER") || e.type().equals("DONE")));
    }
}
