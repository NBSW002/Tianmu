package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.core.TaskTypes.Specification;
import java.util.*;

/** Frozen task -> analysis request. Tools cannot add a source or reinterpret the original words. */
public final class FeedbackTaskPolicy {
    private FeedbackTaskPolicy(){}
    public static Request request(Specification task){
        if(task==null || task.reading()==null || task.reading().textSources().isEmpty())throw new AgentException("TASK_CONSTRAINT_VIOLATION","No feedback sources selected");
        DanmakuTypes.Query query;Long start=task.reading().videoStartMs(),end=task.reading().videoEndMs();
        if(task.typed() instanceof TaskConditions.Report report && Set.of("audience.report","danmaku.report","comments.analysis").contains(task.type()))query=report.query();
        else if(task.typed() instanceof TaskConditions.Interaction draft && draft.feedback()!=null){query=draft.feedback();start=draft.video().startMs();end=draft.video().endMs();}
        else throw new AgentException("TASK_CONSTRAINT_VIOLATION","Task has no frozen feedback interval");
        var sources=EnumSet.noneOf(Source.class);for(var s:task.reading().textSources())sources.add(Source.valueOf(s.name()));
        var grounding=task.reading().includes(AnalysisContract.Source.VIDEO)?Grounding.VIDEO_GROUNDED:Grounding.FEEDBACK_ONLY;
        return new Request(query,sources,grounding,grounding==Grounding.VIDEO_GROUNDED?start:null,grounding==Grounding.VIDEO_GROUNDED?end:null,"feedback-analysis-v1",task.reading().forceReanalysis());
    }
    public static Request pipelineRequest(Specification task){var r=request(task);return new Request(r.query(),r.sources(),r.grounding(),r.videoStartMs(),r.videoEndMs(),com.tianmu.agent.feedback.FeedbackPreparationService.VERSION,r.forceReanalysis());}
    public static Request compressedRequest(Specification task){var r=request(task);return new Request(r.query(),r.sources(),r.grounding(),r.videoStartMs(),r.videoEndMs(),com.tianmu.agent.feedback.FeedbackCompression.VERSION,r.forceReanalysis());}
    public static void require(Specification task,Request supplied){
        if(!request(task).equals(supplied) && !pipelineRequest(task).equals(supplied) && !compressedRequest(task).equals(supplied))throw new AgentException("TASK_CONSTRAINT_VIOLATION","Analysis differs from prepared task");
    }
}
