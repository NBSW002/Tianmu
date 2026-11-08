package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.Status;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.preparation.PreparationService;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Application entry pipeline. AgentRuntime only runs after preparation chooses HARNESS. */
@Component
public class RequestOrchestrator {
    @org.springframework.beans.factory.annotation.Autowired private com.tianmu.agent.application.ContextLifecycle contexts;
    private final PreparationService preparation;
    private final AgentRuntime runtime;
    private final RuntimeHarness harness;
    private final com.tianmu.agent.application.DeliveryService delivery;
    private final com.tianmu.agent.application.TaskAssembler assembler;
    public RequestOrchestrator(PreparationService preparation, AgentRuntime runtime, RuntimeHarness harness) {
        this(preparation, runtime, harness, new com.tianmu.agent.application.DeliveryService(harness),
                new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(), new com.tianmu.agent.prompt.PromptCatalog(), new com.tianmu.agent.tool.ToolCatalog()));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RequestOrchestrator(PreparationService preparation, AgentRuntime runtime, RuntimeHarness harness,
            com.tianmu.agent.application.DeliveryService delivery, com.tianmu.agent.application.TaskAssembler assembler) {
        this.preparation = preparation; this.runtime = runtime; this.harness = harness;
        this.delivery = delivery; this.assembler = assembler;
        this.contexts=new com.tianmu.agent.application.ContextLifecycle(null,harness);
    }
    public void execute(RunState run) {
        try {
            assembler.verifyPolicy(run.executionPolicy());
            harness.execute(run, () -> {
                if (!run.start()) return;
                contexts.begin(run);
                String input = harness.stage(run, "agent.input", null, null, Map.of(),
                        () -> {
                            com.tianmu.agent.harness.trace.TraceDetails.input(run.query());
                            return harness.transform(run, TransformPoint.INPUT, run.query());
                        });
                harness.stage(run, "agent.route", HookPoint.PRE_ROUTE, null, Map.of(), () -> true);
                Result result = harness.stage(run, "agent.preparation", null, null, Map.of(), () -> {
                    com.tianmu.agent.harness.trace.TraceDetails.input(input);
                    return preparation.prepare(run, input);
                });
                run.prepared(result);
                switch (result.route()) {
                    case DIRECT -> delivery.acknowledgement(run,result.reply());
                    case FAQ -> delivery.faq(run, result.faq());
                    case CLARIFY -> runtime.clarifyPrepared(run, result.clarification());
                    case HARNESS -> {
                        contexts.recall(run);
                        var task = harness.stage(run, "agent.assemble", null, null, Map.of(), () -> {
                            com.tianmu.agent.harness.trace.TraceDetails.input(Map.of("preparation", result, "memory", run.memory()));
                            return assembler.assemble(run);
                        });
                        if(run.task()!=task)run.task(task); runtime.runPrepared(run, task);
                    }
                }
            });
        } catch (AgentException e) { run.fail(e.code()); }
        catch (Exception e) { run.fail("INTERNAL_ERROR"); }
    }
    public void resume(RunState run){
        try{assembler.verifyPolicy(run.executionPolicy());harness.execute(run,()->{
            if(run.snapshot().status()!=Status.RUNNING || run.task()==null)throw new AgentException("INVALID_OPERATION_STATE","No resumable prepared task");
            boolean direct=runtime.canCompleteWithoutModel(run);
            contexts.resume(run,direct);
            assembler.verify(run.task());runtime.runPrepared(run,run.task());
        });}
        catch(AgentException e){run.fail(e.code());}catch(Exception e){run.fail("INTERNAL_ERROR");}
    }
}
