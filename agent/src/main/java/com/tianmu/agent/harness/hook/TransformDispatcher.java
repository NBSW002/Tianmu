package com.tianmu.agent.harness.hook;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(HookPolicy.class)
public class TransformDispatcher implements AutoCloseable {
    private record Registered(TransformHook<?> hook, String name, TransformPoint<?> point, int order) {}
    private final List<Registered> hooks;
    private final Tracer tracer;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor workers;
    private final HookPolicy policy;
    public TransformDispatcher(List<TransformHook<?>> hooks, Tracer tracer, ObjectMapper mapper) {
        this(hooks,tracer,mapper,HookPolicy.defaults());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TransformDispatcher(List<TransformHook<?>> hooks, Tracer tracer, ObjectMapper mapper,HookPolicy policy) {
        Set<String> names = new HashSet<>();
        List<Registered> registered = new ArrayList<>();
        for (TransformHook<?> hook : hooks) {
            if (hook.name() == null || !hook.name().matches("[a-z][a-z0-9_.-]{0,63}")
                    || !names.add(hook.name()) || hook.point() == null)
                throw new IllegalArgumentException("Invalid or duplicate transform hook registration");
            registered.add(new Registered(hook, hook.name(), hook.point(), hook.order()));
        }
        this.hooks = registered.stream().sorted(Comparator.comparingInt(Registered::order).thenComparing(Registered::name)).toList();
        this.tracer = tracer;
        this.mapper = mapper;
        this.policy=policy;
        workers=new ThreadPoolExecutor(policy.transformWorkers(),policy.transformWorkers(),0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(policy.queueCapacity()),r->{var t=new Thread(r,"agent-hook-transform");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public <T> T apply(TransformPoint<T> point, T input, TransformContext context, Runnable checkpoint) {
        TransformInvariants.validate(point, input, input);
        bounded(input, 1024 * 1024);
        T current = input;
        for (Registered hook : hooks) {
            if (hook.point() != point) continue;
            checkpoint.run();
            Span span = tracer.spanBuilder("hook.transform_" + point.name()).startSpan();
            TraceNode.begin(span, "hook.transform_" + point.name());
            TraceAttributes.apply(span, Map.of("run.id", context.runId().toString(), "hook.name", hook.name(),
                    "hook.point", point.name(), "hook.mode", "TRANSFORM"));
            try (Scope scope = span.makeCurrent(); TraceLogScope mdc = new TraceLogScope(span, context.runId().toString())) {
                TraceDetails.input(current);
                T prior = current;
                int beforeBytes = mapper.writeValueAsBytes(current).length;
                T next;
                try { next = timedInvoke(hook.hook(), point, current, context); }
                catch (HookRejectedException e) { throw new AgentException("HOOK_REJECTED", "Transform rejected operation"); }
                catch (AgentException e) {
                    if (Set.of("HOOK_TRANSFORM_INVALID","HOOK_TIMEOUT","HOOK_CAPACITY_EXCEEDED","HOOK_INTERRUPTED").contains(e.code())) throw e;
                    throw new AgentException("HOOK_TRANSFORM_FAILED", "Transform hook failed");
                }
                catch (Exception e) {
                    span.setAttribute("exception.type", e.getClass().getSimpleName());
                    throw new AgentException("HOOK_TRANSFORM_FAILED", "Transform hook failed");
                }
                checkpoint.run();
                TransformInvariants.validate(point, input, next);
                bounded(next, 1024 * 1024);
                current = next;
                TraceDetails.output(next);
                TraceAttributes.apply(span, Map.of("context.input_bytes", beforeBytes, "context.output_bytes", mapper.writeValueAsBytes(next).length, "context.changed", !Objects.equals(prior, next)));
                TraceNode.success(span);
            } catch (AgentException e) {
                TraceNode.failure(span, e);
                span.setStatus(StatusCode.ERROR);
                TraceAttributes.apply(span, Map.of("hook.failed", true, "error.code", e.code()));
                throw e;
            } catch (java.io.IOException e) {
                TraceAttributes.apply(span, Map.of("node.status", "FAILED"));
                span.setStatus(StatusCode.ERROR);
                throw new AgentException("HOOK_TRANSFORM_INVALID", "Transform payload cannot be serialized");
            } finally { span.end(); }
            checkpoint.run();
        }
        checkpoint.run();
        bounded(current, point == TransformPoint.CONTEXT ? 256 * 1024 : 64 * 1024);
        return current;
    }
    private <T>T timedInvoke(TransformHook<?> hook,TransformPoint<T> point,T value,TransformContext context) {
        var captured=io.opentelemetry.context.Context.current();var span=Span.current();
        // Every supported payload is immutable; a late worker cannot mutate the accepted projection.
        var task=new FutureTask<T>(()->{try(var scope=captured.makeCurrent();var logs=new TraceLogScope(span,context.runId().toString())){return invoke(hook,point,value,context);}});
        try {workers.execute(task);return task.get(policy.transformTimeoutMillis(),TimeUnit.MILLISECONDS);}
        catch(RejectedExecutionException full){throw new AgentException("HOOK_CAPACITY_EXCEEDED","Transform capacity is unavailable");}
        catch(TimeoutException timeout){throw new AgentException("HOOK_TIMEOUT","Transform deadline expired");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AgentException("HOOK_INTERRUPTED","Transform was interrupted");}
        catch(ExecutionException failed){if(failed.getCause() instanceof RuntimeException runtime)throw runtime;throw new AgentException("HOOK_TRANSFORM_FAILED","Transform failed");}
        finally {if(!task.isDone())task.cancel(true);workers.remove(task);}
    }
    @jakarta.annotation.PreDestroy public void close(){workers.shutdownNow();}
    private void bounded(Object value, int bytes) {
        try {
            if (mapper.writeValueAsBytes(value).length > bytes)
                throw new AgentException("HOOK_PAYLOAD_TOO_LARGE", "Transform payload exceeds harness limit");
        } catch (AgentException e) { throw e; }
        catch (Exception e) { throw new AgentException("HOOK_TRANSFORM_INVALID", "Transform payload cannot be serialized"); }
    }
    @SuppressWarnings("unchecked")
    private static <T> T invoke(TransformHook<?> hook, TransformPoint<T> point, T value, TransformContext context) {
        Object result = ((TransformHook<T>) hook).transform(value, context);
        if (result == null || !point.type().isInstance(result))
            throw new AgentException("HOOK_TRANSFORM_INVALID", "Transform returned an invalid type");
        return point.type().cast(result);
    }
}
