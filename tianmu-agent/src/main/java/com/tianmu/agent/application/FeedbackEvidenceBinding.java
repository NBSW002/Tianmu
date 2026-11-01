package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.EvidenceContext;
import java.util.*;

/** Called only after the analysis service authorized and resolved the immutable asset. */
public final class FeedbackEvidenceBinding {
    private FeedbackEvidenceBinding(){}
    public static Evidence bind(EvidenceContext context,FeedbackAnalysis.Asset asset){
        context.checkpoint();FeedbackTaskPolicy.require(context.task()==null?null:context.task().specification(),asset.request());asset.requireCompatible(context.scope(),asset.request());
        var existing=context.evidenceStore().snapshot().values().stream().filter(e->e.runId().equals(context.runId()) && e.owner().equals(context.scope()) && e.feedback()!=null && e.feedback().jobId().equals(asset.jobId())).findFirst();
        if(existing.isPresent())return existing.get();
        var evidence=new Evidence("ev_"+UUID.randomUUID(),context.runId(),context.scope(),null,null,null,null,null,FeedbackAnalysis.Binding.from(asset));
        context.evidenceStore().put(evidence);return evidence;
    }
}
