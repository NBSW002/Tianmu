package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;
import java.util.stream.IntStream;

/** Relevance scores correspond to the frozen document order. */
public interface FeedbackReranker {
    int CANDIDATES_PER_BUCKET = 20;
    int EVIDENCE_PER_BUCKET = 5;

    record Result(List<Double> scores, Long inputTokens) {
        public Result {
            scores = List.copyOf(scores);
            if (scores.stream().anyMatch(score -> !Double.isFinite(score))
                    || inputTokens != null && inputTokens < 0) {
                throw new AgentException("RERANK_PROTOCOL_ERROR", "Invalid relevance scores or usage");
            }
        }
    }

    Result rank(String query, List<String> documents, CallControl control);

    static List<Integer> topEvidence(int candidates, Result result) {
        if (candidates < 0 || candidates > CANDIDATES_PER_BUCKET
                || result != null && result.scores().size() != candidates) {
            throw new AgentException("RERANK_PROTOCOL_ERROR", "Invalid candidate count");
        }
        var indices = IntStream.range(0, candidates).boxed();
        if (result != null) {
            indices = indices.sorted(Comparator.<Integer>comparingDouble(result.scores()::get)
                    .reversed().thenComparingInt(Integer::intValue));
        }
        return indices.limit(EVIDENCE_PER_BUCKET).toList();
    }
}
