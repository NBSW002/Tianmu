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
        var evidence=context.evidence(ToolArguments.text(call,"evidence_id"));
        if(evidence.feedback()==null || context.analysisContract()==null || !context.analysisContract().reportRequired())throw new AgentException("TASK_CONSTRAINT_VIOLATION","A requested feedback report requires completed analysis evidence");
        context.checkFeedback(evidence.feedback().request());var asset=service.asset(evidence.feedback().jobId(),context.scope(),evidence.feedback().request());
        var report=context.saveReport(evidence.id(),service.report(asset));
        return new ToolResult(name(),Map.of("artifact_id",report.artifactId(),"url",report.url(),"status","READY"),evidence.id(),
                new Answer((asset.model().equals("demo-scripted-feedback")?"【模拟数据】":"")+"视频"+asset.request().query().videoId()+"的反馈报告已生成。已完整处理所选来源 "+asset.snapshot().counts().total()+" 条记录，作者发言单独标记。\n[查看 HTML 报告]("+report.url()+")（需登录鉴权）",List.of(evidence.id())));
    }
}
