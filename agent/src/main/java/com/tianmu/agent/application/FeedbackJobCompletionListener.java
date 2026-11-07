package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentTypes.Snapshot;
import com.tianmu.agent.core.FeedbackAnalysis.Phase;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import com.tianmu.agent.runtime.*;
import org.springframework.stereotype.Component;

@Component
public final class FeedbackJobCompletionListener implements RunCompletionListener {
    private final FeedbackAnalysisService service;
    public FeedbackJobCompletionListener(FeedbackAnalysisService service){this.service=service;}
    public void completed(RunState run,Snapshot snapshot){var job=service.forRun(run.id);if(job!=null && job.phase()!=Phase.READY)service.cancel(job.id());}
    public void discarded(RunState run){completed(run,run.snapshot());}
}
