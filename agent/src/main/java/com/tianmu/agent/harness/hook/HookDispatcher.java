package com.tianmu.agent.harness.hook;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.*;

@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(HookPolicy.class)
public class HookDispatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);
    private record Registered(AgentHook hook, String name, AgentHook.Mode mode, Set<HookPoint> points, int order) {}
    private final List<Registered> hooks;
    private final Tracer tracer;
    private final LongAdder observerFailures = new LongAdder();
    private final HookPolicy policy;
    private final ThreadPoolExecutor gates,observers;
    public HookDispatcher(List<AgentHook> hooks, Tracer tracer) {
        this(hooks,tracer,HookPolicy.defaults());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public HookDispatcher(List<AgentHook> hooks, Tracer tracer,HookPolicy policy) {
        Set<String> names = new HashSet<>();
        for (AgentHook hook : hooks) {
            if (hook.name() == null || !hook.name().matches("[a-z][a-z0-9_.-]{0,63}") || !names.add(hook.name()))
                throw new IllegalArgumentException("Hook names must be unique safe identifiers");
            if (hook.points() == null || hook.points().isEmpty() || hook.mode() == null)
                throw new IllegalArgumentException("Hook points and mode required");
            if (hook.mode() == AgentHook.Mode.GATE && hook.points().stream().anyMatch(point -> !point.gateAllowed()))
                throw new IllegalArgumentException("Gates can only subscribe to pre-execution points");
        }
        this.hooks = hooks.stream().map(hook -> new Registered(hook, hook.name(), hook.mode(), Set.copyOf(hook.points()), hook.order()))
                .sorted(Comparator.comparingInt(Registered::order).thenComparing(Registered::name)).toList();
        this.tracer = tracer;
        this.policy=policy;gates=pool("agent-hook-gate",policy.gateWorkers(),policy.queueCapacity());observers=pool("agent-hook-observer",policy.observerWorkers(),policy.queueCapacity());
    }
    private static ThreadPoolExecutor pool(String name,int workers,int queue) {
        return new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(queue),r->{var t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public void fire(HookEvent event) {
        for (Registered hook : hooks) {
            if (!hook.points().contains(event.point())) continue;
            Span span = tracer.spanBuilder("hook." + event.point().name().toLowerCase(Locale.ROOT)).startSpan();
            TraceNode.begin(span, "hook." + event.point().name().toLowerCase(Locale.ROOT));
            TraceAttributes.apply(span, event.attributes());
            TraceAttributes.apply(span, Map.of("run.id", event.runId().toString(), "hook.name", hook.name(),
                    "hook.point", event.point().name(), "hook.mode", hook.mode().name()));
            try (Scope scope = span.makeCurrent(); TraceLogScope mdc = new TraceLogScope(span, event.runId().toString())) {
                TraceDetails.input(event);
                var context=io.opentelemetry.context.Context.current();
                var executor=hook.mode()==AgentHook.Mode.GATE?gates:observers;
                var task=new FutureTask<Void>(()->{try(var ignored=context.makeCurrent();var logs=new TraceLogScope(span,event.runId().toString())){hook.hook().onEvent(event);return null;}});
                try {
                    executor.execute(task);
                    task.get(hook.mode()==AgentHook.Mode.GATE?policy.gateTimeoutMillis():policy.observerTimeoutMillis(),TimeUnit.MILLISECONDS);
                } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw interrupted; }
                finally { if(!task.isDone())task.cancel(true);executor.remove(task); }
                TraceDetails.output(Map.of("completed", true));
                TraceNode.success(span);
            } catch (Exception e) {
                span.setStatus(StatusCode.ERROR);
                TraceAttributes.apply(span, Map.of("node.status", "FAILED"));
                TraceAttributes.apply(span, Map.of("hook.failed", true, "exception.type", e.getClass().getSimpleName()));
                if (hook.mode() == AgentHook.Mode.GATE) throw new AgentException(e instanceof TimeoutException?"HOOK_TIMEOUT":e instanceof RejectedExecutionException?"HOOK_CAPACITY_EXCEEDED":e instanceof InterruptedException?"HOOK_INTERRUPTED":"HOOK_REJECTED", "A harness gate could not approve this operation");
                observerFailures.increment();
                log.warn("hook={} point={} observer_failed type={}", hook.name(), event.point(), e.getClass().getSimpleName());
            } finally { span.end(); }
        }
    }
    public long observerFailureCount() { return observerFailures.sum(); }
    /** Cancelling a Future never releases a worker still executing an uncooperative hook. */
    @jakarta.annotation.PreDestroy public void close(){gates.shutdownNow();observers.shutdownNow();}
}
