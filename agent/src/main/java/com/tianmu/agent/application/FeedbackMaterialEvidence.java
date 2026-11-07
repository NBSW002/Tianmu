package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.runtime.RunState;
import java.util.*;

/** Current-run material references expose statistics only, not source or representative bodies. */
public final class FeedbackMaterialEvidence {
    private FeedbackMaterialEvidence(){}
    public static ToolResult bind(RunState run,FeedbackJobStore.Job job,FeedbackCompression.Material material){
        var data=new LinkedHashMap<String,Object>(material.summary());data.put("job_id",job.id().toString());data.put("snapshot_hash",material.manifest().sha256());
        var facts=new LookupFacts("prepare_feedback_materials",LookupFacts.normalize(data),Set.of(job.request().query().videoId()));
        var existing=run.execution().evidence().snapshot().values().stream().filter(e->e.lookup()!=null && e.lookup().operation().equals(facts.operation()) && e.lookup().data().equals(facts.data())).findFirst();
        var evidence=existing.orElseGet(()->{var e=new Evidence("ev_"+UUID.randomUUID(),run.id,run.owner,null,null,null,null,null,null,facts);run.execution().evidence().put(e);return e;});
        return new ToolResult("prepare_feedback_materials",Map.of("status","READY","materials_id",evidence.id(),"summary",facts.data()),evidence.id());
    }
}
