package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class CreateFeedbackReportTool implements AgentTool {
    private final FeedbackAnalysisService service;
    public CreateFeedbackReportTool(FeedbackAnalysisService service){this.service=service;}
    public String name(){return "create_feedback_report";}
    public ToolResult execute(ToolCall call,ToolContext context){
        if(!call.arguments().containsKey("evidence_id"))return context.createPreparedReport(
                call.arguments().containsKey("comment_evidence_id")?ToolArguments.text(call,"comment_evidence_id"):null,
                call.arguments().containsKey("danmaku_evidence_id")?ToolArguments.text(call,"danmaku_evidence_id"):null);
        if(call.arguments().size()!=1)throw new AgentException("INVALID_ARGUMENTS","Do not mix legacy analysis and source evidence");
        var evidence=context.evidence(ToolArguments.text(call,"evidence_id"));
        if(evidence.feedback()==null || context.analysisContract()==null || !context.analysisContract().reportRequired())throw new AgentException("TASK_CONSTRAINT_VIOLATION","A requested feedback report requires completed analysis evidence");
        context.checkFeedback(evidence.feedback().request());var asset=service.asset(evidence.feedback().jobId(),context.scope(),evidence.feedback().request());
        var document=service.reportDocument(asset);
        var report=context.saveReport(evidence.id(),FeedbackReportRenderer.render(document));
        return document.immediateResult(name(),report,evidence.id());
    }
}
