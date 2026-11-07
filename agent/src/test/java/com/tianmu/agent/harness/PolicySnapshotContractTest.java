package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.config.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.runtime.store.*;
import com.tianmu.agent.application.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class PolicySnapshotContractTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    ExecutionPolicyFactory factory(boolean enabled) {
        return new ExecutionPolicyFactory(new PolicySnapshotProperties(enabled),LoopPolicy.defaults(),ContextPolicy.defaults(),new PreparationPolicy(Duration.ofMillis(300),Duration.ofSeconds(8)),com.tianmu.agent.feedback.FeedbackPolicy.defaults());
    }
    RunState fresh(RunBackend backend) {
        Instant now=Instant.now();return new RunState(UUID.randomUUID(),UUID.randomUUID(),1,new AccessScope("policy","alice",Set.of(1001L)),"test","test",now,now.plusSeconds(30),backend,new com.tianmu.agent.runtime.events.InMemoryRunEventStore(),new InMemoryEvidenceStore(),false);
    }
    @Test void writerOffAndLegacyJsonNeverAcquireASnapshot() throws Exception {
        var run=fresh(RunBackend.MEMORY);assertNull(factory(false).capture(run.createdAt,null));
        var saved=json.readValue(json.writeValueAsBytes(run.snapshot()),Snapshot.class);run.restore(saved);
        try(var control=new LoopControl(LoopPolicy.defaults())){control.arm(run);}
        assertNull(run.executionPolicy());assertFalse(json.writeValueAsString(run.snapshot()).contains("executionPolicy"));
        assertEquals("LEGACY_CURRENT_POLICY",com.tianmu.agent.harness.trace.TraceSemantics.identity(run).get("policy.source"));
    }
    @Test void queuedRunConsumesItsCapturedLimitsDespiteCurrentConfigurationChanges() throws Exception {
        var run=fresh(RunBackend.MEMORY);var fixed=factory(true).capture(run.createdAt,null);run.initializePolicy(fixed);
        var changed=new LoopPolicy(1,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),1,false,1,1024,128);
        try(var control=new LoopControl(changed)){control.arm(run);assertEquals(fixed.initialDeadline(),run.deadline());assertEquals(16,run.modelCallLimit());assertEquals(6,RunPolicies.loop(run,changed).maxTurns());}
        assertEquals(fixed,json.readValue(json.writeValueAsBytes(run.snapshot()),Snapshot.class).executionPolicy());
        assertEquals(fixed.context(),run.callControl().withTextOutput(t->{}).withBudget(run.modelBudget()).frozenContext());
        assertEquals(8192,RunPolicies.context(fixed.context()).recentMaxBytes());
    }
    @Test void waitingResumePreservesUnknownUsageAndTheSamePolicyHash() throws Exception {
        var run=fresh(RunBackend.MEMORY);run.initializePolicy(factory(true).capture(run.createdAt,null));run.start();
        ModelBudget.execute(run.callControl(),"fixture",false,()->json.createObjectNode());
        var operation=new PendingOperation(UUID.randomUUID(),"feedback-analysis",run.deadline());
        var call=new ToolCall("fixture",Map.of());run.execution().observe(call,new ToolResult("fixture",Map.of(),null,null,operation));run.waitFor(operation);
        Snapshot saved=json.readValue(json.writeValueAsBytes(run.snapshot()),Snapshot.class);
        var continuation=json.readValue(json.writeValueAsBytes(run.continuation()),PendingOperation.Continuation.class);
        var restored=new RunState(run.id,run.sessionId,1,run.owner,run.message,run.query(),run.createdAt,run.deadline(),RunBackend.MEMORY,new com.tianmu.agent.runtime.events.InMemoryRunEventStore(),new InMemoryEvidenceStore(),true);
        restored.restore(saved);restored.restoreContinuation(continuation);
        var before=restored.modelBudget().snapshot();restored.configureBudget(new LoopPolicy(1,Duration.ofSeconds(5),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),1,false));
        assertEquals(before,restored.modelBudget().snapshot());assertEquals(run.executionPolicy().contentHash(),restored.continuation().policyHash());
        assertEquals(8192,before.charged());assertEquals(1,before.unknown());
    }
    @Test void taskWindowAndPolicyBindInOneSaveAndRollBackTogether() {
        var fail=new AtomicBoolean();var writes=new ArrayList<Snapshot>();
        var backend=new RunBackend(){public void save(RunState run){if(fail.get())throw new AgentException("STORAGE_UNAVAILABLE","fixture");writes.add(run.snapshot());}};
        var run=fresh(backend);var initial=factory(true).capture(run.createdAt,null);run.initializePolicy(initial);run.start();
        var spec=TaskMeaning.defaults().resolve("视频1001的弹幕和评论报告，不读取视频内容",run.businessDate,run.createdAt);
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.NEUTRAL,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec),null,null));
        var assembler=new TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog());
        fail.set(true);assertThrows(AgentException.class,()->assembler.assemble(run));assertNull(run.task());assertEquals(initial,run.executionPolicy());assertEquals(initial.initialDeadline(),run.deadline());
        fail.set(false);int before=writes.size();var task=assembler.assemble(run);assertEquals(before+1,writes.size());
        var stored=writes.get(writes.size()-1);assertEquals(task,stored.task());assertEquals(task.policyHash(),stored.executionPolicy().contentHash());
        assertEquals(run.createdAt.plus(Duration.ofHours(1)),task.deadline());assertEquals(task.deadline(),stored.executionPolicy().effectiveDeadline());
    }
    @Test void unsupportedPolicyVersionsAndCorruptionAreExplicitAndLiveRevocationStillApplies() {
        var run=fresh(RunBackend.MEMORY);var fixed=factory(true).capture(run.createdAt,null);
        var unsupported=new ExecutionPolicySnapshot("2",fixed.semanticsVersion(),fixed.contentHash(),fixed.acceptedAt(),fixed.initialDeadline(),fixed.effectiveDeadline(),fixed.windowReason(),fixed.foreground(),fixed.timeouts(),fixed.context(),fixed.business(),fixed.model());
        assertEquals("POLICY_VERSION_UNAVAILABLE",assertThrows(AgentException.class,unsupported::verify).code());
        var corrupt=new ExecutionPolicySnapshot(fixed.schemaVersion(),fixed.semanticsVersion(),"invalid",fixed.acceptedAt(),fixed.initialDeadline(),fixed.effectiveDeadline(),fixed.windowReason(),fixed.foreground(),fixed.timeouts(),fixed.context(),fixed.business(),fixed.model());
        assertEquals("POLICY_SNAPSHOT_INVALID",assertThrows(AgentException.class,corrupt::verify).code());
        run.initializePolicy(fixed);run.memoryGuard(()->{throw new AgentException("MEMORY_CONTEXT_INVALIDATED","revoked");});
        assertEquals("MEMORY_CONTEXT_INVALIDATED",assertThrows(AgentException.class,()->run.callControl().beforeDispatch()).code());assertEquals(fixed,run.executionPolicy());
    }
    @Test void signedIllegalWindowsAndContextLimitsFailAtTheReaderBoundary() {
        var fixed=factory(true).capture(Instant.now(),null);
        var longWindow=new ExecutionPolicySnapshot(fixed.schemaVersion(),fixed.semanticsVersion(),null,fixed.acceptedAt(),fixed.acceptedAt().plusSeconds(86400),fixed.acceptedAt().plusSeconds(86400),fixed.windowReason(),fixed.foreground(),fixed.timeouts(),fixed.context(),fixed.business(),fixed.model()).signed();
        assertEquals("POLICY_SNAPSHOT_INVALID",assertThrows(AgentException.class,longWindow::verify).code());
        var subMillis=new ExecutionPolicySnapshot(fixed.schemaVersion(),fixed.semanticsVersion(),null,fixed.acceptedAt(),fixed.acceptedAt().plusNanos(500000),fixed.acceptedAt().plusNanos(500000),fixed.windowReason(),fixed.foreground(),fixed.timeouts(),fixed.context(),fixed.business(),fixed.model()).signed();
        assertEquals("POLICY_SNAPSHOT_INVALID",assertThrows(AgentException.class,subMillis::verify).code());
        var c=fixed.context();var badContext=new ExecutionPolicySnapshot.Context(c.maxInputBytes(),c.maxInputBytes()+1,c.recentMessages(),c.recentBytes(),c.envelopeVersion(),c.projectionVersion(),c.slotPolicyVersion());
        var illegal=new ExecutionPolicySnapshot(fixed.schemaVersion(),fixed.semanticsVersion(),null,fixed.acceptedAt(),fixed.initialDeadline(),fixed.effectiveDeadline(),fixed.windowReason(),fixed.foreground(),fixed.timeouts(),badContext,fixed.business(),fixed.model()).signed();
        assertEquals("POLICY_SNAPSHOT_INVALID",assertThrows(AgentException.class,illegal::verify).code());
        assertDoesNotThrow(()->fixed.finalized(fixed.acceptedAt().plusMillis(fixed.business().reportWindowMillis()),"FEEDBACK",12,Map.of()).verify());
    }
    @Test void promptContentChangesCannotReuseADeclaredVersionSilently() {
        var fixed=factory(true).capture(Instant.now(),null);
        var changed=new com.tianmu.agent.prompt.PromptCatalog(){
            @Override public Prompt get(String id){var original=super.get(id);return new Prompt(original.id(),original.version(),original.content()+" changed");}
        };
        var assembler=new TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),changed,new com.tianmu.agent.tool.ToolCatalog());
        assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->assembler.verifyPolicy(fixed)).code());
        assertTrue(fixed.business().resources().containsKey("prompt-content:harness.system"));
    }
    @Test void priorReleasedResourceManifestUsesItsOriginalCompatibilityContract() throws Exception {
        var fixture=json.readTree(getClass().getResourceAsStream("/compat/agent-59a1746-resource-manifest.json"));
        Map<String,String> legacy=json.convertValue(fixture.get("resourceVersions"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});
        Set<String> tools=json.convertValue(fixture.get("allowedTools"),new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){});
        var run=fresh(RunBackend.MEMORY);run.start();
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.UNKNOWN,Set.of(),Set.of(),Intent.KNOWLEDGE,new com.tianmu.agent.core.TaskTypes.Specification("unclassified",Map.of(),Map.of())),null,null));
        var assembler=new TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog());
        var task=assembler.assemble(run);
        var previous=new com.tianmu.agent.core.TaskTypes.PreparedTask(task.runId(),task.originalQuery(),task.query(),task.businessDate(),task.timezone(),task.scope(),task.preparation(),task.specification(),legacy,tools,task.deadline(),task.maxModelCalls());
        assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->assembler.verify(previous)).code());assertFalse(previous.resourceVersions().containsKey("manifest:format"));
        var fixed=factory(true).capture(run.createdAt,null);assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->assembler.verifyPolicy(fixed.finalized(fixed.initialDeadline(),"PREPARATION",fixed.foreground().configuredTurns(),legacy))).code());
        assertEquals("2",assembler.resourceVersions().get("manifest:format"));
        var partial=new HashMap<>(assembler.resourceVersions());partial.remove("prompt-content:harness.system");
        assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->assembler.verifyPolicy(fixed.finalized(fixed.initialDeadline(),"PREPARATION",fixed.foreground().configuredTurns(),partial))).code());
        run.cancel();
    }
}
