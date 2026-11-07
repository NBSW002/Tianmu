package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.runtime.RunState;
import java.util.*;

public final class FeedbackSourceEvidence {
    private FeedbackSourceEvidence(){}
    public static String tool(Source source){return source==Source.COMMENT?"analyze_comments":"analyze_danmaku";}
    public static ToolResult bind(RunState run,FeedbackJobStore.Job job,FeedbackPreparationService.Prepared source){
        var data=LookupFacts.normalize(Map.of("job_id",job.id(),"source",source.source(),"snapshot_hash",source.manifest().sha256(),
                "counts",source.plan().snapshot().counts(),"data_as_of",source.plan().snapshot().dataAsOf(),"exact_expressions",source.exactExpressions(),"candidate_clusters",source.clusters(),"method",source.method(),"status","PREPARED"));
        var facts=new LookupFacts(tool(source.source()),data,Set.of(job.request().query().videoId()));
        var evidence=run.execution().evidence().snapshot().values().stream().filter(e->e.lookup()!=null && facts.operation().equals(e.lookup().operation()) && facts.data().equals(e.lookup().data())).findFirst().orElseGet(()->{
            var ev=new Evidence("ev_"+UUID.randomUUID(),run.id,run.owner,null,null,null,null,null,null,facts);run.execution().evidence().put(ev);return ev;
        });
        return new ToolResult(tool(source.source()),Map.of("status","READY","evidence_id",evidence.id(),"summary",data),evidence.id());
    }
}
