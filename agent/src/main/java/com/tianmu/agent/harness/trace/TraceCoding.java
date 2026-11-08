package com.tianmu.agent.harness.trace;

import io.opentelemetry.api.trace.Span;
import java.util.Map;

/** Instrumentation contract for code-producing/executing tools; does not execute code. */
public final class TraceCoding {
    private TraceCoding() {}
    public static void generated(String language,String snippet){
        TraceAttributes.apply(Span.current(),Map.of("code.state","GENERATED","code.language",language));
        TraceDetails.capture("code",Map.of("language",language,"snippet",snippet),"GENERATED_CODE");
    }
    public static void executed(boolean success,Object result){
        TraceAttributes.apply(Span.current(),Map.of("code.execution_state",success?"SUCCEEDED":"FAILED"));
        TraceDetails.capture("execution",result,"CODE_EXECUTION_RESULT");
    }
    public static void fix(int round,String kind){
        if(round<1)throw new IllegalArgumentException("Correction rounds start at one");
        TraceAttributes.apply(Span.current(),Map.of("correction.state","ATTEMPTED","correction.attempted",true,"correction.round",round,"correction.round_state","RECORDED","correction.kind",kind));
    }
}
