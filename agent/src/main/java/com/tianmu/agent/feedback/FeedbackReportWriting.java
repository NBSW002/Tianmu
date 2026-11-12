package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import java.util.*;

/** The writer supplies prose; confirmed titles, evidence and counts remain host-owned. */
public final class FeedbackReportWriting {
    private FeedbackReportWriting() {}
    public static ResultNode validate(String workId,Input input,List<Proposal> proposals){
        if(proposals==null || proposals.size()!=input.children().size())throw invalid();
        var byId=new HashMap<String,Proposal>();
        for(var p:proposals){
            if(p.members().size()!=1 || byId.putIfAbsent(p.members().get(0),p)!=null
                    || p.observation()==null || p.observation().isBlank() || p.observation().length()>2400
                    || p.recommendation()==null || p.recommendation().length()>1200)throw invalid();
        }
        var topics=new ArrayList<Topic>();
        for(var child:input.children()){
            var p=byId.remove(child.id());if(p==null)throw invalid();
            topics.add(new Topic(workId+":T"+(topics.size()+1),child.title(),p.observation(),p.recommendation(),
                    List.of(child.id()),child.messages(),child.authorMessages(),child.selection()));
        }
        if(!byId.isEmpty())throw invalid();
        FeedbackNarrativePolicy.validate(input,proposals);
        return new ResultNode(workId,WorkKind.MERGE,topics,topics.stream().mapToLong(Topic::messages).sum(),
                topics.stream().mapToLong(Topic::authorMessages).sum());
    }
    private static AgentException invalid(){return new AgentException("INVALID_ANALYSIS","Report writing must preserve every confirmed topic");}
}
