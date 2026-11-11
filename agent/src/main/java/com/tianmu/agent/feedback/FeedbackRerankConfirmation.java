package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import java.util.*;

/** Validates the evidence-only report without expanding bucket membership. */
public final class FeedbackRerankConfirmation {
    private FeedbackRerankConfirmation(){}
    public static Map<String,Object> modelEvidence(FeedbackModels.Group group){
        return Map.of("expressionId",group.id(),"groupId",group.candidateCluster(),"source",group.source(),
                "text",group.text(),"context",group.replyContext());
    }

    public static ResultNode validate(String workId,FeedbackModels.Input input,List<FeedbackModels.Proposal> proposals){
        var groups=new HashMap<String,FeedbackModels.Group>();
        for(var group:input.groups()){
            if(groups.putIfAbsent(group.id(),group)!=null)throw new IllegalArgumentException("Duplicate evidence ID");
        }
        var topics=new ArrayList<Topic>();
        var accepted=new ArrayList<Set<String>>();
        var titles=new HashSet<String>();
        for(var proposal:proposals){
            if(topics.size()==5)break;
            var ids=new LinkedHashSet<>(proposal.members());
            var selection=proposal.selection();
            boolean direct = input.groups().stream().allMatch(g -> g.candidateCluster()!=null && g.candidateCluster().startsWith("D"));
            if(ids.size()<(direct?1:2) || ids.size()!=proposal.members().size() || !groups.keySet().containsAll(ids)
                    || selection==null || selection.representativeIds().isEmpty() || selection.representativeIds().size()>3
                    || new HashSet<>(selection.representativeIds()).size()!=selection.representativeIds().size()
                    || !ids.containsAll(selection.representativeIds())
                    || !within(proposal.title(),24) || !within(proposal.observation(),120) || !within(proposal.recommendation(),80)
                    || accepted.stream().anyMatch(previous->previous.containsAll(ids) || ids.containsAll(previous))
                    || !titles.add(proposal.title().strip()))continue;
            long count=ids.stream().flatMap(id->groups.get(id).originalIds().stream()).distinct().count();
            topics.add(new Topic(workId+":T"+(topics.size()+1),proposal.title().strip(),proposal.observation(),proposal.recommendation(),
                    List.copyOf(ids),count,0,selection));
            accepted.add(ids);
        }
        return new ResultNode(workId,WorkKind.CLASSIFY,topics,topics.stream().mapToLong(Topic::messages).sum(),0);
    }

    private static boolean within(String value,int limit){
        return value!=null && !value.isBlank() && value.codePointCount(0,value.length())<=limit;
    }
}
