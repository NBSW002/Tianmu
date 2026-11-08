package com.tianmu.agent.application;

import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.feedback.FeedbackPreparationStore.Step;
import com.tianmu.agent.feedback.FeedbackPreparationStore.Work;
import java.util.*;
import java.util.function.Function;

/** Read accepted operations from the fenced preparation ledger; absent references are never guessed. */
final class FeedbackCausalReferences {
    static Map<String,Object> dependencies(Step step,Set<Source> sources,String traceId,Function<Step,Work> lookup){
        var expected=switch(step){case SNAPSHOT->List.<Step>of();case COMMENT,DANMAKU->List.of(Step.SNAPSHOT);case MATERIALS->sources.stream().map(s->Step.valueOf(s.name())).sorted().toList();default->List.<Step>of();};
        var predecessors=expected.stream().map(lookup).filter(Objects::nonNull).filter(work->"COMPLETE".equals(work.state())).map(Work::acceptedBy).filter(Objects::nonNull).filter(ref->ref.traceId().equals(traceId)).map(ref->ref.spanId()).toList();
        return Map.of("analysis.predecessor_span_ids",predecessors,"analysis.dependencies_complete",predecessors.size()==expected.size());
    }
}
