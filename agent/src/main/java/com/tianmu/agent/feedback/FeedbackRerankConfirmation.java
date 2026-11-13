package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import java.util.*;

/** Validates the evidence-only report without expanding bucket membership. */
public final class FeedbackRerankConfirmation {
    private FeedbackRerankConfirmation(){}
    public static Map<String,Object> modelEvidence(FeedbackModels.Group group){
        var row=new LinkedHashMap<String,Object>();
        row.put("expressionId",group.id());row.put("groupId",group.candidateCluster());row.put("source",group.source());
        row.put("text",group.text());row.put("context",group.replyContext());
        row.put("playbackMs",group.playbackMs());row.put("exactMessages",group.messages()-group.replies().size());
        row.put("likes",group.likes());row.put("likesAvailable",group.likes()!=null);
        row.put("replies",group.replies());
        if(!group.replies().isEmpty()){
            var replyIds=group.replies().stream().map(FeedbackModels.Reply::id).collect(java.util.stream.Collectors.toSet());
            row.put("rootCommentIds",group.originalIds().stream().filter(id->!replyIds.contains(id)).toList());
        }
        return Collections.unmodifiableMap(row);
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
                    || !within(proposal.title(),24) || !within(proposal.observation(),120) || !optional(proposal.recommendation(),80)
                    || accepted.stream().anyMatch(previous->previous.containsAll(ids) || ids.containsAll(previous))
                    || ids.stream().allMatch(id->FeedbackTextQuality.lowInformation(groups.get(id).text()))
                    || !titles.add(proposal.title().strip()))continue;
            long count=ids.stream().flatMap(id->groups.get(id).originalIds().stream()).distinct().count();
            topics.add(new Topic(workId+":T"+(topics.size()+1),proposal.title().strip(),proposal.observation(),proposal.recommendation(),
                    List.copyOf(ids),count,0,selection));
            accepted.add(ids);
        }
        return new ResultNode(workId,WorkKind.CLASSIFY,topics,topics.stream().mapToLong(Topic::messages).sum(),0);
    }

    private static boolean optional(String value,int limit){
        return value!=null && value.codePointCount(0,value.length())<=limit;
    }
    private static boolean within(String value,int limit){
        return value!=null && !value.isBlank() && value.codePointCount(0,value.length())<=limit;
    }
}
