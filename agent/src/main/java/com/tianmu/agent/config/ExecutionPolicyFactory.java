package com.tianmu.agent.config;

import com.tianmu.agent.core.ExecutionPolicySnapshot;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.model.RuntimeModelCatalog;
import com.tianmu.agent.preparation.PreparationPolicy;
import com.tianmu.agent.runtime.control.LoopPolicy;
import java.time.Instant;
import java.util.Map;

@org.springframework.stereotype.Component
@org.springframework.boot.context.properties.EnableConfigurationProperties({PolicySnapshotProperties.class,ContextPolicy.class})
public final class ExecutionPolicyFactory {
    private final PolicySnapshotProperties writer;
    private final LoopPolicy loop;
    private final ContextPolicy context;
    private final PreparationPolicy preparation;
    private final com.tianmu.agent.feedback.FeedbackPolicy feedback;
    private final com.tianmu.agent.application.TaskAssembler tasks;
    private final com.tianmu.agent.preparation.PreparationModelPort models;
    public ExecutionPolicyFactory(PolicySnapshotProperties writer,LoopPolicy loop,ContextPolicy context,PreparationPolicy preparation,com.tianmu.agent.feedback.FeedbackPolicy feedback) {
        this(writer,loop,context,preparation,feedback,new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog()),null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionPolicyFactory(PolicySnapshotProperties writer,LoopPolicy loop,ContextPolicy context,PreparationPolicy preparation,com.tianmu.agent.feedback.FeedbackPolicy feedback,
            com.tianmu.agent.application.TaskAssembler tasks,com.tianmu.agent.preparation.PreparationModelPort models) {
        this.writer=writer;this.loop=loop;this.context=context;this.preparation=preparation;this.feedback=feedback;
        this.tasks=tasks;this.models=models;
    }
    public ExecutionPolicySnapshot capture(Instant acceptedAt,RuntimeModelCatalog.Choice model) {
        if(!writer.enabled())return null;
        var deadline=acceptedAt.plus(loop.requestTimeout());
        return new ExecutionPolicySnapshot("1","agent-execution-v1",null,acceptedAt,deadline,deadline,"PREPARATION",
            new ExecutionPolicySnapshot.Foreground(loop.maxModelCalls(),loop.maxTokens(),loop.tokenReservation(),loop.maxTurns(),loop.maxTurns(),loop.consecutiveToolFailures(),loop.finalizeEnabled(),"analysis-turns-v1"),
            new ExecutionPolicySnapshot.Timeouts(loop.modelTimeout().toMillis(),loop.toolTimeout().toMillis(),loop.finalizeTimeout().toMillis(),120000,
                preparation.faqTimeout().toMillis(),preparation.understandingTimeout().toMillis(),preparation.emotionTimeout().toMillis(),preparation.intentTimeout().toMillis(),preparation.deterministicRouting(),preparation.emotionEnabled()),
            new ExecutionPolicySnapshot.Context(context.maxInputBytes(),context.maxHistoryBytes(),context.recentMaxMessages(),context.recentMaxBytes(),"1.0","chat_native_v1","slots-v1"),
            new ExecutionPolicySnapshot.Business(feedback.timeout().toMillis(),"conditions-v1","completion-v1",tasks==null?Map.of():tasks.resourceVersions()),
            model==null?new ExecutionPolicySnapshot.Model("host-default","host-default","host-v1","chat-native-v1",models==null?"preparation-v1":models.revision()):
                new ExecutionPolicySnapshot.Model(model.id(),model.model(),model.revision(),"chat-native-v1",models==null?"preparation-v1":models.revision())).signed();
    }
}
