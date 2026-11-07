package com.tianmu.agent.harness.trace;

import io.opentelemetry.context.*;
import io.opentelemetry.api.trace.Span;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Stage-local measured operations. Database time includes pool/lock waits, not just server SQL execution. */
public final class IoTimings {
    public enum Kind { FEEDBACK_OBJECT, FEEDBACK_DATABASE }
    public static final ContextKey<IoTimings> KEY=ContextKey.named("tianmu.trace.io");
    private final Map<Kind,LongAdder> nanos=new EnumMap<>(Kind.class),calls=new EnumMap<>(Kind.class);
    private final ThreadLocal<Set<Kind>> active=ThreadLocal.withInitial(()->EnumSet.noneOf(Kind.class));
    public IoTimings(){for(var kind:Kind.values()){nanos.put(kind,new LongAdder());calls.put(kind,new LongAdder());}}
    public static <T> T measure(Kind kind,Supplier<T> operation){
        var timing=Context.current().get(KEY);if(timing==null || !timing.active.get().add(kind))return operation.get();
        long start=System.nanoTime();
        try{return operation.get();}
        finally{timing.nanos.get(kind).add(System.nanoTime()-start);timing.calls.get(kind).increment();timing.active.get().remove(kind);if(timing.active.get().isEmpty())timing.active.remove();}
    }
    public void export(Span span){
        for(var kind:Kind.values())if(calls.get(kind).sum()>0){String prefix="io."+kind.name().toLowerCase(Locale.ROOT);
            TraceAttributes.apply(span,Map.of(prefix+"_calls",calls.get(kind).sum(),prefix+"_ms",nanos.get(kind).sum()/1_000_000.0));}
    }
}
