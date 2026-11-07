package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.resource.TextPage;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ReadResultTool implements AgentTool {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public String name() { return "read_result"; }
    public boolean batchable(){return true;}
    public boolean parallelSafe(){return true;}
    public int order() { return 30; }
    public Exposure exposure() { return Exposure.GENERAL; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        Evidence evidence = context.evidence(ToolArguments.text(call, "result_id"));
        if(evidence.lookup()!=null && evidence.lookup().operation().equals("read_report")){
            if("original_text".equals(call.arguments().get("section")))return new ToolResult(name(),context.readReportOriginal(evidence,
                    ToolArguments.text(call,"topic_id"),ToolArguments.text(call,"original_id"),
                    call.arguments().containsKey("offset")?Math.toIntExact(ToolArguments.integer(call,"offset")):0,
                    call.arguments().containsKey("limit")?Math.toIntExact(ToolArguments.integer(call,"limit")):2048),evidence.id());
            var result=context.readReport(java.util.UUID.fromString(evidence.lookup().data().get("source_run_id").toString()),evidence.lookup().data().get("artifact_id").toString(),
                    call.arguments().containsKey("section")?ToolArguments.text(call,"section"):"summary",
                    call.arguments().containsKey("offset")?Math.toIntExact(ToolArguments.integer(call,"offset")):0,
                    call.arguments().containsKey("limit")?Math.min(10,Math.toIntExact(ToolArguments.integer(call,"limit"))):10);
            return new ToolResult(name(),result.data(),result.evidenceId());
        }
        if(evidence.feedback()!=null && "original_text".equals(call.arguments().get("section")))return new ToolResult(name(),context.readFeedbackOriginal(evidence,
                ToolArguments.text(call,"topic_id"),ToolArguments.text(call,"original_id"),
                call.arguments().containsKey("offset")?Math.toIntExact(ToolArguments.integer(call,"offset")):0,
                call.arguments().containsKey("limit")?Math.toIntExact(ToolArguments.integer(call,"limit")):2048),evidence.id());
        if(evidence.feedback()!=null)return new ToolResult(name(),context.readFeedback(evidence,
                call.arguments().containsKey("section")?ToolArguments.text(call,"section"):"summary",
                call.arguments().containsKey("topic_id")?ToolArguments.text(call,"topic_id"):null,
                call.arguments().containsKey("offset")?Math.toIntExact(ToolArguments.integer(call,"offset")):0,
                call.arguments().containsKey("limit")?Math.min(10,Math.toIntExact(ToolArguments.integer(call,"limit"))):10),evidence.id());
        final String body;
        try { body = JSON.writeValueAsString(evidence.facts()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new com.tianmu.agent.core.AgentException("INVALID_EVIDENCE", "Cannot serialize evidence");
        }
        if (call.arguments().containsKey("offset") || call.arguments().containsKey("limit")
                || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192) {
            int offset = call.arguments().containsKey("offset") ? Math.toIntExact(ToolArguments.integer(call, "offset")) : 0;
            int limit = call.arguments().containsKey("limit") ? Math.toIntExact(ToolArguments.integer(call, "limit")) : 2048;
            return new ToolResult(name(), Map.of("evidence_id", evidence.id(), "format", "json",
                    "page", TextPage.read(body, offset, limit)), evidence.id());
        }
        if(evidence.lookup()!=null)return new ToolResult(name(),com.tianmu.agent.core.LookupFacts.normalize(Map.of("resource",evidence.lookup())),evidence.id());
        return new ToolResult(name(), Map.of(evidence.comments() != null ? "comments" : evidence.analysis() == null ? "metric" : "analysis", evidence.facts()), evidence.id());
    }
}
