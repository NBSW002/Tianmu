package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.common.CompletableResultCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceRecordTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    @Test void identitiesAreStableAcrossReconstructionAndSeparateTaskRunAndTurn(){
        var scope=new AccessScope("t","u",Set.of(1001L));var run=new RunState(scope,"query");var a=TraceSemantics.identity(run);
        var restored=new RunState(run.id,run.sessionId,run.turnSeq,scope,run.message,run.query(),run.createdAt,run.deadline(),com.tianmu.agent.runtime.store.RunBackend.MEMORY,new com.tianmu.agent.runtime.events.InMemoryRunEventStore(),new com.tianmu.agent.runtime.store.InMemoryEvidenceStore(),true);
        assertEquals(a,TraceSemantics.identity(restored));assertNotEquals(a.get("task.id"),run.id.toString());assertNotEquals(a.get("task.id"),a.get("turn.id"));assertNotEquals(a.get("task.id"),TraceSemantics.identity(new RunState(scope,"query")).get("task.id"));
    }
    @Test void promptAndCodeDetailsArePrivateBoundedAndExportedOnlyAsReferences()throws Exception{
        var captured=new ArrayList<SpanData>();var exporter=exporter(captured);var details=new TraceDetails(new FileObjectStore(directory),json,4096,true);var runId=UUID.randomUUID();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()){
            var tracer=provider.get("test");var span=tracer.spanBuilder("agent.tool").startSpan();TraceNode.begin(span,"agent.tool");
            try(var scope=details.bind(Context.current().with(span),runId).makeCurrent()){
                TracePrompt.call("harness.system","1.0.0","FINALIZE","PRIVATE_PROMPT",List.of("harness.system@1.0.0"),()->{TracePrompt.capture(List.of(Map.of("role","system","content","PRIVATE_PROMPT"),Map.of("role","user","content","PRIVATE_QUERY")));return null;});
                TraceCoding.generated("python","password=supersecret");TraceCoding.executed(true,Map.of("exitCode",0));TraceCoding.fix(1,"CODE_REPAIR");
            }finally{span.end();}
        }
        var envelope=TraceEnvelope.from(captured.get(0),"test","test");var record=TraceRecord.from(envelope);
        assertEquals("harness.system",record.get("prompt_id"));assertEquals("FINALIZE",record.get("prompt_variant"));assertEquals(true,record.get("fix_attempt"));assertEquals(1L,record.get("fix_round"));assertNull(record.get("prompt_text"));assertNull(record.get("code_snippet"));assertNotNull(record.get("prompt_text_ref"));assertNotNull(record.get("code_snippet_ref"));assertNotNull(record.get("execution_result_ref"));assertFalse(json.writeValueAsString(record).contains("PRIVATE_PROMPT"));assertFalse(envelope.attributes().toString().contains("supersecret"));
        var attrs=envelope.attributes();assertEquals(TracePrompt.hash("6:system14:PRIVATE_PROMPT"),attrs.get("prompt.effective_hash"));
        String[] ref=record.get("code_snippet_ref").toString().split("/");String body=new String(details.read(runId,ref[0],ref[1],Long.parseLong(ref[2]),ref[3]),java.nio.charset.StandardCharsets.UTF_8);assertFalse(body.contains("supersecret"));assertTrue(body.contains("REDACTED"));assertTrue(body.contains("python"));
    }
    @Test void oldRecordsStayUnknownAndRoleDoesNotGetInferredFromTheNodeName(){
        var span=new TraceEnvelope("1.1","e","agent","test","a".repeat(32),"b".repeat(16),"0".repeat(16),"agent.model","INTERNAL",1,2,1,"UNSET",Map.of());var record=TraceRecord.from(span);
        for(String field:List.of("task_id","turn_id","role","prompt_id","fix_round","fix_attempt","response_time"))assertNull(record.get(field),field);
        assertEquals("NOT_CAPTURED",record.get("prompt_text_state"));assertEquals("UNKNOWN",record.get("result"));assertEquals("NOT_APPLICABLE",record.get("tool_input_state"));assertNull(((Map<?,?>)record.get("token_usage")).get("input"));
    }
    @Test void protectedCaptureIsNotExportedAsCompleteContent()throws Exception{
        var captured=new ArrayList<SpanData>();var details=new TraceDetails(new FileObjectStore(directory),json,4096,true);
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter(captured))).build()){
            var span=provider.get("test").spanBuilder("model.request").startSpan();TraceNode.begin(span,"model.request");
            try(var scope=details.bind(Context.current().with(span),UUID.randomUUID()).makeCurrent()){TraceDetails.protectMemory();TracePrompt.inline("memory.test","1","EXTRACTION","DO_NOT_EXPOSE");}finally{span.end();}
        }
        var envelope=TraceEnvelope.from(captured.get(0),"test","test");assertEquals("SOURCE_PROTECTED",TraceRecord.from(envelope).get("prompt_text_state"));assertFalse(envelope.attributes().toString().contains("DO_NOT_EXPOSE"));
    }
    static SpanExporter exporter(List<SpanData> captured){return new SpanExporter(){public CompletableResultCode export(Collection<SpanData> data){captured.addAll(data);return CompletableResultCode.ofSuccess();}public CompletableResultCode flush(){return CompletableResultCode.ofSuccess();}public CompletableResultCode shutdown(){return flush();}};}
}
