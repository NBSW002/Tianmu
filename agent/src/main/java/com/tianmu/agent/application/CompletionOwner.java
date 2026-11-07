package com.tianmu.agent.application;

import com.tianmu.agent.runtime.spi.CompletionEvaluator;
import java.util.Set;

/** Exactly one owner per task type; '*' is the sole fallback for ordinary tasks. */
public interface CompletionOwner {
    Set<String> taskTypes();
    CompletionEvaluator.Decision evaluate(CompletionEvaluator.Input input);
}
