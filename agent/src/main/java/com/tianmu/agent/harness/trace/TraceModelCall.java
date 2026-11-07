package com.tianmu.agent.harness.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.Map;
import java.util.function.Supplier;

/** A model request is a child of the durable work step, never its replacement. */
public final class TraceModelCall {
    private TraceModelCall() {}
    private record Binding(Tracer tracer, Map<String,Object> identity) {}
    private static final ContextKey<Binding> KEY = ContextKey.named("tianmu.trace.model-call");
    public static Context bind(Context context, Tracer tracer, Map<String,Object> identity) {
        return context.with(KEY,new Binding(tracer,Map.copyOf(identity)));
    }
    public static Map<String,Object> identity(){var binding=Context.current().get(KEY);return binding==null?Map.of():binding.identity();}
    public static <T> T call(String name, Map<String, ?> attributes, Supplier<T> operation) {
        var binding=Context.current().get(KEY);
        if(binding==null)return operation.get();
        var merged=new java.util.HashMap<String,Object>(attributes);
        merged.putAll(binding.identity());
        return call(binding.tracer(), name, merged, operation);
    }
    public static <T> T call(Tracer tracer, String name, Map<String, ?> attributes, Supplier<T> operation) {
        var span = tracer.spanBuilder(name).startSpan();
        TraceNode.begin(span, name);
        TraceAttributes.apply(span, attributes);
        try (var scope = span.makeCurrent()) {
            T result = operation.get();
            TraceDetails.output(result);
            TraceNode.success(span);
            return result;
        } catch (RuntimeException error) {
            TraceNode.failure(span, error);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            throw error;
        } finally { span.end(); }
    }
}
