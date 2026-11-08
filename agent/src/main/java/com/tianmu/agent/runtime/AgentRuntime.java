package com.tianmu.agent.runtime;

import com.tianmu.agent.core.StopReason;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.tool.ToolDispatcher;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.runtime.control.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.harness.trace.*;

@Component
public class AgentRuntime {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.runtime.spi.ContextAuthority contextAuthority;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ToolBatchExecutor toolBatches;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ToolBatchLedger batchLedger;
    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final Set<String> RETRYABLE = Set.of("TOOL_CALL_TIMEOUT", "TOOL_TEMPORARY_ERROR");
    private final String finalizeDirective;
    private final ModelPort model;
    private final ToolDispatcher tools;
    private final com.tianmu.agent.runtime.spi.ResponseDelivery delivery;
    private final com.tianmu.agent.runtime.spi.TaskExecutionPolicy taskPolicy;
    private final com.tianmu.agent.runtime.spi.CompletionEvaluator completion;
    private final RuntimeHarness harness;
    private static final class Progress {
        final Set<ToolCall> successfulCalls = new HashSet<>();
        String failingTool;
        int consecutiveFailures;
        int argumentCorrections;
        void success(ToolCall call) { successfulCalls.add(new ToolCall(call.name(),call.arguments())); failingTool = null; consecutiveFailures = 0; }
        int failure(String name) {
            consecutiveFailures = name.equals(failingTool) ? consecutiveFailures + 1 : 1;
            failingTool = name;
            return consecutiveFailures;
        }
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentRuntime(ModelPort model,ToolDispatcher tools,RuntimeHarness harness,PromptCatalog prompts,
            com.tianmu.agent.runtime.spi.ResponseDelivery delivery,com.tianmu.agent.runtime.spi.TaskExecutionPolicy taskPolicy,
            com.tianmu.agent.runtime.spi.CompletionEvaluator completion) {
        this.model=model;this.tools=tools;this.harness=harness;this.delivery=delivery;this.taskPolicy=taskPolicy;this.completion=completion;
        this.finalizeDirective=prompts.text("harness.finalize");
    }
    public void execute(RunState run) {
        try { harness.execute(run, () -> loop(run)); }
        catch (AgentException e) { run.fail(e.code()); }
        catch (Exception e) {
            log.error("run_id={} failed type={}", run.id, e.getClass().getSimpleName());
            run.fail("INTERNAL_ERROR");
        }
    }
    private void loop(RunState run) {
        if (!run.start()) return;
        String input = harness.stage(run, "agent.input", null, null, Map.of(),
                () -> harness.transform(run, TransformPoint.INPUT, run.message));
        harness.stage(run, "agent.route", HookPoint.PRE_ROUTE, null, Map.of(), () -> true);
        runLoop(run, input);
    }
    void runPrepared(RunState run, com.tianmu.agent.core.TaskTypes.PreparedTask task) {
        if (run.task() != task) throw new IllegalStateException("Prepared task must be bound before execution");
        runLoop(run, task.query());
    }
    /** The host exposes only this tool before task assembly; no loop/model turn is needed. */
    void clarifyPrepared(RunState run, String question) {
        if (run.task() != null || run.snapshot().route() != Route.CLARIFY)
            throw new AgentException("INVALID_OPERATION_STATE", "No pending preparation clarification");
        var allowed = tools.definitions(run).stream().filter(t -> t.name().equals(tools.clarificationCall(question).name())).toList();
        var context = new ModelContext(run.query(), run.businessDate, run.owner, Set.of(), List.of(), allowed,
                0, false, "", run.preparedRequest(), null, run.memory());
        executeTool(run, context, tools.clarificationCall(question), new Progress(), false);
    }
    private void runLoop(RunState run, String message) {
        List<String> available = tools.definitions(run).stream()
                .map(ToolDefinition::name).toList();
        Map<String, Object> start = new HashMap<>();
        start.put("agent.max_turns", maxTurns(run));
        start.put("agent.available_tools", available.stream().limit(32).toList());
        start.put("agent.available_tool_count", available.size());
        if (run.modelOr(model).modelName() != null) start.put("model.name", run.modelOr(model).modelName());
        harness.marker(run, SpanType.AGENT_START, start);
        boolean returned = false;
        try { runLoopBody(run, message); returned = true; }
        finally {
            Map<String, Object> end = new HashMap<>();
            end.put("agent.total_turns", run.snapshot().steps());
            end.put("agent.reached_turn_limit", run.snapshot().steps() >= maxTurns(run));
            end.put("agent.outcome", !returned ? "ERROR" : run.snapshot().status().name());
            TraceUsage usage = io.opentelemetry.context.Context.current().get(TraceUsage.KEY);
            if (usage != null) end.putAll(usage.totals());
            harness.marker(run, SpanType.AGENT_END, end);
        }
    }
    public static com.tianmu.agent.runtime.spi.CompletionEvaluator.Input completionInput(RunState run) {
        return new com.tianmu.agent.runtime.spi.CompletionEvaluator.Input(run.task(),run.execution().exchanges(),run.execution().artifactEvidence(),null,null,com.tianmu.agent.core.StopReason.ANSWER,run.execution().evidence().snapshot());
    }
    public boolean canCompleteWithoutModel(RunState run) {
        return completion.evaluate(completionInput(run)) instanceof com.tianmu.agent.runtime.spi.CompletionEvaluator.Deliver;
    }
    private void runLoopBody(RunState run,String message) {
        if(complete(run,null,null,StopReason.ANSWER,null))return;
        run.emit("PROGRESS", Map.of("stage", "harness"));
        Progress progress = new Progress();
        for(var exchange:run.execution().exchanges()){
            if(!exchange.result().data().containsKey("error_code"))progress.success(exchange.call());
            else{progress.failure(exchange.call().name());if(exchange.result().data().containsKey("input_validation"))progress.argumentCorrections++;
                if(exchange.result().data().get("batch_validation") instanceof Map<?,?> validation && validation.get("correction") instanceof Number count)
                    progress.argumentCorrections=Math.max(progress.argumentCorrections,count.intValue());}
        }
        int max = maxTurns(run);
        for (int step = run.snapshot().steps(); step < max; step++) {
            run.checkpoint();
            run.nextStep();
            final int remaining = max - step;
            boolean done = harness.stage(run, "agent.loop", null, null, Map.of(),
                    () -> iteration(run, message, remaining, progress));
            if (done) return;
        }
        if (!harness.policy(run).finalizeEnabled() || run.execution().evidence().isEmpty()) { run.fail("STEP_LIMIT"); return; }
        finalizeWithoutTools(run, message);
    }
    private int maxTurns(RunState run){
        return run.executionPolicy()==null?taskPolicy.maxTurns(policyInput(run),harness.policy(run).maxTurns()):run.executionPolicy().foreground().effectiveTurns();
    }
    private com.tianmu.agent.runtime.spi.TaskExecutionPolicy.Input policyInput(RunState run){return new com.tianmu.agent.runtime.spi.TaskExecutionPolicy.Input(run.task(),run.modelCallLimit(),run.execution().evidence().snapshot(),run.execution().artifactEvidence());}
    private ModelContext context(RunState run, String message, int remaining, boolean finalizing) {
        return harness.stage(run, "agent.context", null, null, Map.of("context.observation_count", run.execution().observations().size()),
                () -> harness.transform(run, TransformPoint.CONTEXT, new ModelContext(message,
                        run.businessDate, run.owner, run.execution().activeSkills(), run.execution().observations(),
                        finalizing ? List.of() : tools.definitions(run), remaining, finalizing,
                        finalizing ? finalizeDirective : "", run.preparedRequest(), run.task(), run.memory(),run.execution().exchanges(),
                        taskPolicy.project(policyInput(run)))));
    }
    private Decision decide(RunState run, ModelContext context) {
        if(contextAuthority!=null)contextAuthority.verify(run);
        return harness.stage(run, context.finalizing() ? "agent.model.finalize" : "agent.model", HookPoint.PRE_MODEL, HookPoint.POST_MODEL,
                Map.of("run.finalizing", context.finalizing(), "loop.step", run.snapshot().steps(),
                        "agent.available_tools", context.availableTools().stream().map(ToolDefinition::name).limit(32).toList()), () -> {
            TraceDetails.capture("input", context, "MODEL_CONTEXT");
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(), Map.of("model.name", run.modelOr(model).modelName() == null ? "unspecified" : run.modelOr(model).modelName(),
                    "model.streaming", run.modelOr(model).streamsText(), "model.first_token_state", run.modelOr(model).streamsText()?"PENDING":"NOT_APPLICABLE"));
            Decision raw = harness.modelCall(run, context.finalizing(), control -> context.finalizing()
                    ? run.modelOr(model).finalizeWithoutTools(context, control) : run.modelOr(model).decide(context, control));
            if (raw == null || raw.kind() == null) throw new AgentException("INVALID_DECISION", "Missing model decision");
            return harness.transform(run, TransformPoint.MODEL_RESULT, raw);
        });
    }
    private boolean iteration(RunState run, String message, int remaining, Progress progress) {
        ModelContext context = context(run, message, remaining, false);
        Decision decision = decide(run, context);
        run.checkpoint();
        return switch (decision.kind()) {
            case TOOL -> {
                if(decision.additionalCalls().isEmpty())yield executeTool(run,context,decision.call(),progress,true);
                var batch=new ArrayList<ToolCall>();batch.add(decision.call());batch.addAll(decision.additionalCalls());
                yield executeBatch(run,context,batch,progress);
            }
            case CLARIFY -> executeTool(run,context,tools.clarificationCall(decision.clarification()),progress,true);
            case ANSWER -> complete(run,decision.answer(),null,StopReason.ANSWER,null);
        };
    }
    private boolean executeBatch(RunState run,ModelContext context,List<ToolCall> requested,Progress progress){
        return harness.stage(run,"agent.tool.batch",null,null,Map.of("tool.batch_count",requested.size()),()->{
            try { tools.checkBatch(requested,run); }
            catch(ToolDispatcher.BatchAdmissionRejected | ToolDispatcher.BatchInputRejected rejected) {
                // No hooks or handlers have run. Pair every native call before asking the model to replan.
                String group=requested.get(0).providerGroupId();
                if(group==null || group.isBlank() || requested.stream().anyMatch(c->c.providerCallId()==null || c.providerCallId().isBlank() || !group.equals(c.providerGroupId()))
                        || requested.stream().map(ToolCall::providerCallId).distinct().count()!=requested.size())
                    throw new AgentException("INVALID_DECISION","Invalid native batch identities");
                if(requested.stream().anyMatch(c->context.availableTools().stream().noneMatch(t->t.name().equals(c.name()))))
                    throw new AgentException("TOOL_NOT_AVAILABLE","Tool is outside the exposed set");
                run.checkpoint();
                if(progress.argumentCorrections>=2)throw new AgentException("TOOL_FAILURE_LIMIT","Tool correction limit reached");
                int correction=++progress.argumentCorrections;
                var feedbacks=new ArrayList<ToolResult>();
                for(var call:requested) {
                    var feedback=new ToolResult(call.name(),Map.of("error_code",rejected.code(),"retryable",true,
                            "batch_validation",Map.of("phase","BEFORE_HANDLER","handler_executed",false,
                                    "correction",correction,"hint",rejected.getMessage())),null);
                    feedbacks.add(harness.transform(run,TransformPoint.TOOL_RESULT,feedback));
                }
                run.checkpoint();
                for(int i=0;i<requested.size();i++)run.execution().observe(requested.get(i),feedbacks.get(i));
                for(var call:requested) {
                    run.emit("PROGRESS",Map.of("stage","tool_failed","tool",call.name(),"code",rejected.code()));
                }
                TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("tool.batch_rejected",true,"tool.argument_correction",correction,
                        "tool.error_code",rejected.code(),"tool.handler_executed",false));
                return false;
            }
            var calls=new ArrayList<ToolCall>();
            for(var original:requested){
                harness.toolHook(run,HookPoint.PRE_TOOL,Map.of("tool.name",original.name()));
                var actual=harness.transform(run,TransformPoint.TOOL_CALL,original);
                if(!Objects.equals(original.providerCallId(),actual.providerCallId()) || !Objects.equals(original.providerGroupId(),actual.providerGroupId()))throw new AgentException("INVALID_DECISION","Hook changed tool call identity");
                if(progress.successfulCalls.contains(new ToolCall(actual.name(),actual.arguments())))throw new AgentException("NO_PROGRESS","Repeated successful tool call");
                calls.add(actual);
            }
            for(var call:calls)if(context.availableTools().stream().noneMatch(tool->tool.name().equals(call.name())))throw new AgentException("TOOL_NOT_AVAILABLE","Transformed tool is outside the exposed set");
            tools.checkBatch(calls,run); // transforms must not bypass frozen admission or execution-mode checks
            boolean parallel=toolBatches!=null && toolBatches.accepts(calls,run,tools);
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("tool.execution_mode",parallel?"PARALLEL":"SERIAL"));
            if(batchLedger!=null)batchLedger.prepare(run,calls,parallel);
            String batchId=ToolBatchLedger.id(run);
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("tool.batch_id",batchId));
            if(parallel){
                int settlementStart=run.execution().exchanges().size();
                var results=toolBatches.execute(run,calls,tools);
                int callIndex=0;
                for(var item:results){
                    var result=harness.transform(run,TransformPoint.TOOL_RESULT,item.result());
                    item.branch().validate(result);if(item.success())item.branch().commit(result);
                    harness.toolHook(run,HookPoint.POST_TOOL,Map.of("tool.name",item.call().name(),"tool.success",item.success()));
                    if(item.success())progress.success(item.call());
                    if(batchLedger!=null)batchLedger.complete(run,callIndex,result,item.success());
                    run.execution().observe(item.call(),result);
                    run.emit("PROGRESS",Map.of("stage",item.success()?"tool_completed":"tool_failed","tool",item.call().name(),"batch_id",batchId,"call_id",item.call().providerCallId()==null?"native-"+callIndex:item.call().providerCallId(),"call_index",callIndex++));
                }
                return completeBatch(run,run.execution().exchanges().subList(settlementStart,run.execution().exchanges().size()).stream().map(ToolExchange::result).toList());
            }
            int settlementStart=run.execution().exchanges().size();
            boolean done=false;
            for(int index=0;index<calls.size();index++){
                var call=calls.get(index);done=executeTool(run,context,call,progress,false,true,true);
                var exchanges=run.execution().exchanges();
                if(batchLedger!=null && run.snapshot().status()==Status.RUNNING && !exchanges.isEmpty()){
                    var exchange=exchanges.get(exchanges.size()-1);
                    if(exchange.call().equals(call))batchLedger.complete(run,index,exchange.result(),!exchange.result().data().containsKey("error_code"));
                }
                if(done)break;
            }
            if(!done)return completeBatch(run,run.execution().exchanges().subList(settlementStart,run.execution().exchanges().size()).stream().map(ToolExchange::result).toList());
            return done;
        });
    }
    private boolean executeTool(RunState run, ModelContext context, ToolCall call, Progress progress, boolean allowCorrection) {
        return executeTool(run,context,call,progress,allowCorrection,false);
    }
    private boolean executeTool(RunState run,ModelContext context,ToolCall call,Progress progress,boolean allowCorrection,boolean deferWait){
        return executeTool(run,context,call,progress,allowCorrection,deferWait,false);
    }
    private boolean executeTool(RunState run,ModelContext context,ToolCall call,Progress progress,boolean allowCorrection,boolean deferWait,boolean prepared){
        if (call == null) throw new AgentException("INVALID_DECISION", "Missing tool call");
        if (context.availableTools().stream().noneMatch(tool -> tool.name().equals(call.name())))
            throw new AgentException("TOOL_NOT_AVAILABLE", "Tool is not exposed in the current model context");
        String name = call.name();
        run.emit("PROGRESS", Map.of("stage", "tool", "tool", name));
        ToolResult result;
        var executed = new java.util.concurrent.atomic.AtomicReference<ToolCall>(call);
        var inputRejected = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            int attempt = name.equals(progress.failingTool) ? progress.consecutiveFailures + 1 : 1;
            result = harness.stage(run, "agent.tool", prepared?null:HookPoint.PRE_TOOL, HookPoint.POST_TOOL,
                    Map.of("tool.name", name, "execution.attempt", attempt, "execution.retry_count", attempt - 1), () -> {
                TraceDetails.input(call);
                ToolCall actual = prepared?call:harness.transform(run, TransformPoint.TOOL_CALL, call);
                if (context.availableTools().stream().noneMatch(tool -> tool.name().equals(actual.name())))
                    throw new AgentException("TOOL_NOT_AVAILABLE", "Transformed tool is outside the exposed set");
                executed.set(actual);
                if (progress.successfulCalls.contains(new ToolCall(actual.name(),actual.arguments()))) throw new AgentException("NO_PROGRESS", "Repeated successful tool call");
                ToolDispatcher.DispatchOutcome dispatched=allowCorrection?tools.dispatch(actual,run):new ToolDispatcher.Completed(tools.execute(actual,run));
                ToolResult raw;
                if(dispatched instanceof ToolDispatcher.ValidationRejected rejected) {
                    run.checkpoint();
                    int failure=progress.failure(name);
                    if(progress.argumentCorrections>=2 || failure>=harness.policy(run).consecutiveToolFailures())
                        throw new AgentException("TOOL_FAILURE_LIMIT","Tool argument correction limit reached");
                    progress.argumentCorrections++;inputRejected.set(true);
                    TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("tool.validation_issue_count",rejected.validation().issues().size(),
                            "tool.argument_correction",progress.argumentCorrections));
                    raw=new ToolResult(name,Map.of("error_code","INVALID_ARGUMENTS","retryable",true,"attempt",failure,
                            "input_validation",Map.of("phase","BEFORE_HANDLER","handler_executed",false,
                                    "issues",rejected.validation().issues().stream().map(issue->Map.of("path",issue.path(),"code",issue.code().name(),"hint",issue.code().hint())).toList(),
                                    "issues_truncated",rejected.validation().truncated())),null);
                } else raw=((ToolDispatcher.Completed)dispatched).result();
                ToolResult admitted = harness.transform(run, TransformPoint.TOOL_RESULT, raw);
                if(!inputRejected.get())progress.success(actual);
                return admitted;
            });
        } catch (AgentException e) {
            run.checkpoint();
            if (!RETRYABLE.contains(e.code())) throw e;
            int attempt = progress.failure(name);
            if (attempt >= harness.policy(run).consecutiveToolFailures())
                throw new AgentException("TOOL_FAILURE_LIMIT", "Consecutive tool failures reached the limit");
            Map<String,Object> feedback = new HashMap<>(Map.of("error_code", e.code(), "retryable", true, "attempt", attempt));
            result = harness.transform(run, TransformPoint.TOOL_RESULT, new ToolResult(name, feedback, null));
            run.execution().observe(executed.get(),result);
            run.emit("PROGRESS", Map.of("stage", "tool_failed", "tool", name, "code", e.code(), "attempt", attempt));
            return false;
        }
        run.execution().observe(executed.get(),result);
        if(inputRejected.get()) {
            run.emit("PROGRESS",Map.of("stage","tool_failed","tool",name,"code","INVALID_ARGUMENTS","attempt",progress.consecutiveFailures));
            return false;
        }
        run.emit("PROGRESS", Map.of("stage", "tool_completed", "tool", name));
        if(deferWait)return false;
        return complete(run,result.terminalAnswer(),result.terminalClarification(),StopReason.TOOL_DELIVERY,null);
    }
    private boolean completeBatch(RunState run,List<ToolResult> results){
        var answers=results.stream().map(ToolResult::terminalAnswer).filter(Objects::nonNull).toList();
        var questions=results.stream().map(ToolResult::terminalClarification).filter(Objects::nonNull).toList();
        if(answers.size()+questions.size()>1)throw new AgentException("INVALID_TOOL_RESULT","A batch cannot race multiple terminal proposals");
        return complete(run,answers.isEmpty()?null:answers.get(0),questions.isEmpty()?null:questions.get(0),StopReason.TOOL_DELIVERY,null);
    }
    private boolean complete(RunState run,Answer answer,String question,StopReason reason,TurnControl.Reservation reservation){
        run.checkpoint();
        var input=new com.tianmu.agent.runtime.spi.CompletionEvaluator.Input(run.task(),run.execution().exchanges(),run.execution().artifactEvidence(),answer,question,reason,run.execution().evidence().snapshot());
        var decision=completion.evaluate(input);
        if(decision instanceof com.tianmu.agent.runtime.spi.CompletionEvaluator.Deliver done){delivery.answer(run,done.draft(),reservation==null?delivery.reserve(run):reservation,done.reason());return true;}
        if(decision instanceof com.tianmu.agent.runtime.spi.CompletionEvaluator.Clarify clarify){delivery.clarification(run,clarify.question(),reservation==null?delivery.reserve(run):reservation);return true;}
        if(decision instanceof com.tianmu.agent.runtime.spi.CompletionEvaluator.Await waiting){run.waitFor(waiting.operation());return true;}
        if(decision instanceof com.tianmu.agent.runtime.spi.CompletionEvaluator.Reject rejected)throw new AgentException(rejected.code(),"Task completion requirements were not met");
        return false;
    }
    private void finalizeWithoutTools(RunState run, String message) {
        TurnControl.Reservation reservation = delivery.reserve(run);
        harness.stage(run, "agent.finalize", null, null, Map.of("run.finalizing", true), () -> {
            run.emit("PROGRESS", Map.of("stage", "finalizing"));
            ModelContext context = context(run, message, 0, true);
            Decision decision;
            try {
                decision = decide(run, context);
                if (decision.kind() == Kind.TOOL) throw new AgentException("FINALIZATION_TOOL_CALL", "Tools are disabled during finalization");
            } catch (AgentException e) {
                run.checkpoint();
                if (!Set.of("MODEL_CALL_TIMEOUT", "MODEL_ERROR", "INVALID_DECISION", "FINALIZATION_TOOL_CALL").contains(e.code())) throw e;
                if(!complete(run,EvidenceFallbackRenderer.render(run),null,StopReason.FINALIZED_ANSWER,reservation))throw new AgentException("INVALID_ANSWER","Finalization did not produce a terminal decision");
                return true;
            }
            if(!complete(run,decision.kind()==Kind.CLARIFY?null:decision.answer(),decision.kind()==Kind.CLARIFY?decision.clarification():null,StopReason.FINALIZED_ANSWER,reservation))throw new AgentException("INVALID_ANSWER","Finalization did not produce a terminal decision");
            return true;
        });
    }
}
