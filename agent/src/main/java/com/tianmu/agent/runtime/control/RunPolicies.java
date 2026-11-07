package com.tianmu.agent.runtime.control;

import com.tianmu.agent.core.ExecutionPolicySnapshot;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.preparation.PreparationPolicy;
import java.time.Duration;

/** Compatibility reader: absence remains legacy for the complete run lifetime. Never writes a snapshot. */
public final class RunPolicies {
    private RunPolicies(){}
    public static LoopPolicy loop(RunState run,LoopPolicy current) {
        var s=run.executionPolicy();if(s==null)return current;s.verify();var f=s.foreground();var t=s.timeouts();
        return new LoopPolicy(f.effectiveTurns(),Duration.between(s.acceptedAt(),s.initialDeadline()),Duration.ofMillis(t.modelMillis()),Duration.ofMillis(t.toolMillis()),Duration.ofMillis(t.finalizeMillis()),f.consecutiveFailures(),f.finalizeEnabled(),f.maxCalls(),f.tokenLimit(),f.tokenReservation());
    }
    public static PreparationPolicy preparation(RunState run,PreparationPolicy current) {
        var s=run.executionPolicy();if(s==null)return current;s.verify();var t=s.timeouts();
        return new PreparationPolicy(Duration.ofMillis(t.faqMillis()),Duration.ofMillis(t.understandingMillis()),Duration.ofMillis(t.emotionMillis()),Duration.ofMillis(t.intentMillis()),t.deterministicRouting(),t.emotionEnabled());
    }
    public static ContextPolicy context(ExecutionPolicySnapshot.Context c) {
        return new ContextPolicy(c.maxInputBytes(),c.maxHistoryBytes(),c.recentMessages(),c.recentBytes());
    }
}
