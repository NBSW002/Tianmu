package com.tianmu.agent.application;

import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.feedback.FeedbackPreparationStore.Step;
import com.tianmu.agent.feedback.FeedbackPreparationStore.Reference;
import com.tianmu.agent.feedback.MemoryFeedbackPreparationStore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackCausalReferencesTest {
    @Test void linksOnlyAcceptedStepsInTheSameJobAndLeavesMissingDependenciesUnknown(){
        var store=new MemoryFeedbackPreparationStore();var job=UUID.randomUUID();var sources=Set.of(Source.COMMENT,Source.DANMAKU);String trace="1".repeat(32);
        java.util.function.Function<Step,com.tianmu.agent.feedback.FeedbackPreparationStore.Work> lookup=step->store.get(job,step);
        assertEquals(false,FeedbackCausalReferences.dependencies(Step.COMMENT,sources,trace,lookup).get("analysis.dependencies_complete"));
        complete(store,job,Step.SNAPSHOT,new Reference(trace,"aaaaaaaaaaaaaaaa"));
        assertEquals(List.of("aaaaaaaaaaaaaaaa"),FeedbackCausalReferences.dependencies(Step.DANMAKU,sources,trace,lookup).get("analysis.predecessor_span_ids"));
        complete(store,job,Step.COMMENT,new Reference(trace,"cccccccccccccccc"));
        assertEquals(false,FeedbackCausalReferences.dependencies(Step.MATERIALS,sources,trace,lookup).get("analysis.dependencies_complete"));
        complete(store,job,Step.DANMAKU,new Reference(trace,"dddddddddddddddd"));var join=FeedbackCausalReferences.dependencies(Step.MATERIALS,sources,trace,lookup);
        assertEquals(true,join.get("analysis.dependencies_complete"));assertEquals(List.of("cccccccccccccccc","dddddddddddddddd"),join.get("analysis.predecessor_span_ids"));
        assertEquals(false,FeedbackCausalReferences.dependencies(Step.MATERIALS,sources,"2".repeat(32),lookup).get("analysis.dependencies_complete"));
        assertThrows(IllegalArgumentException.class,()->new Reference("0".repeat(32),"0".repeat(16)));
    }
    private static void complete(MemoryFeedbackPreparationStore store,UUID job,Step step,Reference ref){store.request(job,step);var claim=store.claim(job,step,Instant.now(),Duration.ofSeconds(30));assertTrue(store.complete(claim,null,ref));}
}
