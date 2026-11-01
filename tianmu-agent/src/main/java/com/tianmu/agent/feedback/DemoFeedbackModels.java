package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;

/** Explicit deterministic demo; never described as semantic model evaluation. */
public final class DemoFeedbackModels implements FeedbackModels {
    public String modelName(){return "demo-scripted-feedback";}
    public String policyHash(){return FeedbackObjects.sha("demo-feedback-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public List<Proposal> analyze(Input input,CallControl control){
        control.checkpoint();var ids=input.kind()==WorkKind.CLASSIFY?input.groups().stream().map(Group::id).toList():input.children().stream().map(Topic::id).toList();
        return ids.isEmpty()?List.of():List.of(new Proposal("T1","演示反馈","这是确定性演示分类，不代表真实模型的内容判断。","",ids));
    }
}
