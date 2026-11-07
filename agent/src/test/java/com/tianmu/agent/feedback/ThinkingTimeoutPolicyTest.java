package com.tianmu.agent.feedback;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ThinkingTimeoutPolicyTest {
    @Test void thinkingDeadlineBindsWithoutChangingTheOverallJobOrCapacityBudgets(){
        var policy=new Binder(new MapConfigurationPropertySource(Map.of("agent.feedback.model-timeout","120s")))
                .bind("agent.feedback",Bindable.of(FeedbackPolicy.class)).get();
        assertEquals(Duration.ofSeconds(120),policy.modelTimeout());
        assertEquals(Duration.ofHours(1),policy.timeout());assertEquals(1,policy.readParallelism());
        assertEquals(FeedbackPolicy.defaults().maxModelCalls(),policy.maxModelCalls());
        assertEquals(FeedbackPolicy.defaults().maxTokens(),policy.maxTokens());
    }
    @Test void timeoutHasAnExactBoundRatherThanTruncatingFractionalSeconds(){
        var baseline=FeedbackPolicy.defaults();
        assertDoesNotThrow(()->withTimeout(baseline,Duration.ofSeconds(180)));
        assertThrows(IllegalArgumentException.class,()->withTimeout(baseline,Duration.ofSeconds(180).plusNanos(1)));
        assertThrows(IllegalArgumentException.class,()->withTimeout(baseline,Duration.ofMillis(999)));
    }
    private FeedbackPolicy withTimeout(FeedbackPolicy p,Duration timeout){return new FeedbackPolicy(p.maxRecords(),p.maxSnapshotBytes(),p.batchBytes(),p.batchGroups(),p.maxModelCalls(),p.maxTokens(),p.timeout(),timeout,p.readParallelism());}
}
