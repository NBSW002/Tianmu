package com.tianmu.agent.harness.trace;

import java.time.Instant;
import java.util.*;

/** Uniform analysis/export view. Private bodies are references; missing historical facts stay unknown. */
public final class TraceRecord {
    private TraceRecord() {}
    public static Map<String,Object> from(TraceEnvelope span){
        var a=span.attributes();var r=new LinkedHashMap<String,Object>();
        r.put("schema_version","1.0");r.put("trace_id",span.traceId());r.put("span_id",span.spanId());r.put("parent_span_id",span.parentSpanId());
        r.put("timestamp",Instant.ofEpochSecond(Math.floorDiv(span.startTimeUnixNano(),1_000_000_000L),Math.floorMod(span.startTimeUnixNano(),1_000_000_000L)).toString());
        r.put("task_id",a.get("task.id"));r.put("task_scope",a.get("task.scope"));r.put("run_id",a.get("run.id"));r.put("turn_id",a.get("turn.id"));r.put("turn_seq",a.get("run.turn_seq"));r.put("role",a.get("message.role"));
        String content=a.containsKey("detail.output_ref")?"output":"input";detail(r,a,"content",content);
        r.put("model",a.get("model.name"));r.put("model_resolved",a.get("model.resolved_name"));r.put("model_version_state",a.getOrDefault("model.version_state","UNKNOWN"));r.put("model_dispatched",a.get("model.dispatched"));
        r.put("prompt_id",a.get("prompt.id"));r.put("prompt_version",a.get("prompt.version"));r.put("prompt_variant",a.get("prompt.variant"));r.put("prompt_hash",a.get("prompt.hash"));r.put("prompt_effective_hash",a.get("prompt.effective_hash"));detail(r,a,"prompt_text","prompt");
        r.put("response_time",a.get("model.response_ms") instanceof Number n?n.doubleValue()/1000.0:null);r.put("duration_seconds",span.durationNanos()/1_000_000_000.0);
        var usage=new LinkedHashMap<String,Object>();usage.put("input",a.get("model.input_tokens"));usage.put("output",a.get("model.output_tokens"));usage.put("total",a.get("model.total_tokens"));usage.put("state",a.getOrDefault("model.usage_state","UNKNOWN"));r.put("token_usage",Collections.unmodifiableMap(usage));
        r.put("tool_call",a.get("tool.name"));if(a.containsKey("tool.name")){detail(r,a,"tool_input","input");detail(r,a,"tool_output","output");}else for(String field:List.of("tool_input","tool_output")){r.put(field,null);r.put(field+"_ref",null);r.put(field+"_state","NOT_APPLICABLE");}detail(r,a,"error","error");r.put("error_type",a.get("exception.type"));r.put("error_code",a.get("error.code"));r.put("result",a.getOrDefault("node.status",span.status().equals("ERROR")?"FAILED":span.status().equals("OK")?"SUCCEEDED":"UNKNOWN"));
        detail(r,a,"code_snippet","code");r.put("code_state",a.getOrDefault("code.state","UNKNOWN"));detail(r,a,"execution_result","execution");r.put("execution_state",a.getOrDefault("code.execution_state","UNKNOWN"));
        r.put("fix_attempt",a.containsKey("correction.attempted")?a.get("correction.attempted"):"NOT_APPLICABLE".equals(a.get("correction.state"))?false:null);r.put("fix_round",a.get("correction.round"));r.put("fix_state",a.getOrDefault("correction.state","UNKNOWN"));r.put("fix_kind",a.get("correction.kind"));r.put("fix_round_state",a.getOrDefault("correction.round_state","UNKNOWN"));r.put("retry_count",a.get("execution.retry_count"));r.put("final_status",a.get("run.status"));
        return Collections.unmodifiableMap(r);
    }
    private static void detail(Map<String,Object> target,Map<String,Object> a,String field,String direction){
        target.put(field,null);target.put(field+"_ref",a.get("detail."+direction+"_ref"));
        Object state=a.getOrDefault("detail."+direction+"_state","NOT_CAPTURED");
        target.put(field+"_state",Set.of("COMPLETE","TRUNCATED").contains(state) && "SOURCE_PROTECTED".equals(a.get("detail."+direction+"_access"))?"SOURCE_PROTECTED":state);
    }
}
