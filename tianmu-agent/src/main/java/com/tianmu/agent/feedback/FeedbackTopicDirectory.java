package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.KnownTopic;
import com.tianmu.agent.runtime.control.CancellationToken;
import java.time.Duration;
import java.util.*;

/** Disposable projection of an immutable, accepted classification prefix. Never accepts attempted results. */
final class FeedbackTopicDirectory {
    private record Prefix(int batches,List<KnownTopic> topics) {}
    private final FeedbackJobStore store;
    private final FeedbackObjects objects;
    private final com.github.benmanes.caffeine.cache.Cache<UUID,Prefix> prefixes=com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(32).expireAfterAccess(Duration.ofHours(1)).build();

    FeedbackTopicDirectory(FeedbackJobStore store,FeedbackObjects objects){this.store=store;this.objects=objects;}

    List<KnownTopic> before(FeedbackJobStore.Work claim,CancellationToken token){
        if(claim.kind()!=WorkKind.CLASSIFY || claim.level()!=0 || claim.ordinal()<0)throw corrupt();
        var prefix=prefixes.getIfPresent(claim.jobId());
        if(prefix==null || prefix.batches()>claim.ordinal())prefix=new Prefix(0,List.of());
        var topics=new LinkedHashMap<String,KnownTopic>();prefix.topics().forEach(t->topics.put(t.key(),t));
        int next=prefix.batches();
        while(next<claim.ordinal()){
            token.checkpoint();int count=Math.min(64,claim.ordinal()-next);
            var rows=store.completedRange(claim.jobId(),0,next,count);
            if(rows.size()!=count)throw corrupt();
            for(var row:rows){
                token.checkpoint();
                if(!row.jobId().equals(claim.jobId()) || row.kind()!=WorkKind.CLASSIFY || row.level()!=0
                        || row.ordinal()!=next || !row.state().equals("COMPLETE") || row.result()==null)throw corrupt();
                var result=objects.get(row.result(),ResultNode.class);
                if(!result.workId().equals(row.id().toString()) || result.kind()!=WorkKind.CLASSIFY)throw corrupt();
                for(var topic:result.topics()){
                    String owner=row.id()+":";
                    if(!topic.id().startsWith(owner))throw corrupt();
                    String key=topic.id().substring(owner.length());
                    if(!key.matches("T(?:[1-9]|[1-9][0-9]|100)"))throw corrupt();
                    var prior=topics.get(key);
                    topics.put(key,new KnownTopic(key,prior==null?topic.title():prior.title(),
                            prior==null?topic.messages():Math.addExact(prior.messages(),topic.messages())));
                }
                next++;
            }
        }
        token.checkpoint();var accepted=new Prefix(next,List.copyOf(topics.values()));
        prefixes.put(claim.jobId(),accepted);return accepted.topics();
    }

    void invalidate(UUID job){prefixes.invalidate(job);}
    private static AgentException corrupt(){return new AgentException("STORAGE_CORRUPT","Classification prefix is incomplete or inconsistent");}
}
