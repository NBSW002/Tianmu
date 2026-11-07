package com.tianmu.agent.application;

import com.tianmu.agent.runtime.spi.CompletionEvaluator;

/** A named, pure requirement applied to every eligible terminal draft. */
public interface CompletionRequirement {
    String name();
    void verify(CompletionEvaluator.Input input,CompletionEvaluator.Deliver proposal);
}
