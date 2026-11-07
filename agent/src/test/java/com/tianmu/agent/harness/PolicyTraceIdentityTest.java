package com.tianmu.agent.harness;

import com.tianmu.agent.application.TaskAssembler;
import com.tianmu.agent.config.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.control.LoopPolicy;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolicyTraceIdentityTest {
    @Test void exportedRootAndModelChildUseThePolicyEffectiveAtTheirStage() {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var run=fixture.run();
            var factory=new ExecutionPolicyFactory(new PolicySnapshotProperties(true),LoopPolicy.defaults(),ContextPolicy.defaults(),new PreparationPolicy(Duration.ofMillis(300),Duration.ofSeconds(8)),com.tianmu.agent.feedback.FeedbackPolicy.defaults());
            run.initializePolicy(factory.capture(run.createdAt,null));var initial=run.executionPolicy().contentHash();
            fixture.harness.execute(run,()->{
                run.start();
                fixture.harness.stage(run,"test.before",null,null,Map.of(),()->TraceModelCall.call("test.request.before",Map.of(),()->true));
                run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.UNKNOWN,Set.of(),Set.of(),Intent.KNOWLEDGE,new com.tianmu.agent.core.TaskTypes.Specification("unclassified",Map.of(),Map.of())),null,null));
                new TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog()).assemble(run);
                fixture.harness.stage(run,"test.after",null,null,Map.of(),()->TraceModelCall.call("test.request.after",Map.of(),()->true));
                run.clarify("请补充问题");
            });
            var effective=run.executionPolicy().contentHash();assertNotEquals(initial,effective);
            var attributes=new HashMap<String,Map<String,Object>>();fixture.recording.spans.forEach(span->attributes.put(span.getName(),TraceAttributes.from(span.getAttributes())));
            assertEquals(initial,attributes.get("test.request.before").get("policy.hash"));
            assertEquals(effective,attributes.get("test.request.after").get("policy.hash"));
            assertEquals(effective,attributes.get("agent.run").get("policy.hash"));
            assertEquals("SNAPSHOT_V1",attributes.get("agent.run").get("policy.source"));
        }
    }
}
