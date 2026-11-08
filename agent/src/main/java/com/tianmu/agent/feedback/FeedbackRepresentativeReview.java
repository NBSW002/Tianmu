package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import java.util.*;

/** Validates final semantic proposals against the sealed, bounded candidates, never the full corpus. */
public final class FeedbackRepresentativeReview {
    private FeedbackRepresentativeReview() {}
    public static void validate(Input input,List<Proposal> proposals){
        if(input.kind()!=WorkKind.MERGE || input.groups().isEmpty())return;
        var byChild=new HashMap<String,Set<String>>();
        try{
            var json=new ObjectMapper();
            for(var child:input.children()){
                var ids=new HashSet<String>();
                for(var group:input.groups())if(child.members().contains(group.id()))for(var rep:json.readTree(group.text()))ids.add(rep.path("id").asText());
                byChild.put(child.id(),ids);
            }
        }catch(Exception invalid){throw new AgentException("STORAGE_CORRUPT","Invalid sealed review candidates");}
        var allSelected=new HashSet<String>();
        for(var proposal:proposals){
            var selection=proposal.selection();
            if(selection==null)reject(proposals,"REPRESENTATIVE_SELECTION_MISSING");
            var unresolved=new HashSet<String>();if(input.correction()!=null)for(var previous:input.correction().previousTopics())if(previous.selection()!=null && (Set.of("MIXED","EXCLUDED").contains(previous.selection().coherence()) || !previous.selection().distinct()))unresolved.addAll(previous.members());
            if("EXCLUDED".equals(selection.coherence()) && !proposal.members().isEmpty() && unresolved.containsAll(proposal.members())){
                if(!selection.representativeIds().isEmpty() || !selection.frameMs().isEmpty())reject(proposals,"REPRESENTATIVE_SELECTION_INVALID");continue;
            }
            if(!"COHERENT".equals(selection.coherence()) || !selection.distinct())reject(proposals,"REPRESENTATIVE_SEMANTIC_REVIEW_FAILED");
            var eligible=new HashSet<String>();for(String child:proposal.members())eligible.addAll(byChild.getOrDefault(child,Set.of()));
            if(selection.representativeIds().size()!=Math.min(3,eligible.size()) || selection.representativeIds().isEmpty() || !eligible.containsAll(selection.representativeIds()))reject(proposals,"REPRESENTATIVE_SELECTION_INVALID");
            for(String id:selection.representativeIds())if(!allSelected.add(id))reject(proposals,"REPRESENTATIVE_SELECTION_DUPLICATE");
            if(selection.frameMs().size()>2 || new HashSet<>(selection.frameMs()).size()!=selection.frameMs().size())reject(proposals,"REPRESENTATIVE_FRAME_INVALID");
            for(long at:selection.frameMs())if(input.video()==null || !input.video().sampledAtMs().contains(at) || input.video().moments().stream().noneMatch(m->m.atMs()==at))reject(proposals,"REPRESENTATIVE_FRAME_INVALID");
        }
        com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("analysis.semantic_review_passed",true,"analysis.reviewed_topics",proposals.size(),"analysis.review_excluded_topics",proposals.stream().filter(p->"EXCLUDED".equals(p.selection().coherence())).count()));
    }
    private static void reject(List<Proposal> proposals,String issue){throw new FeedbackCorrectionException(new Correction(proposals,List.of(),List.of(),List.of(),issue));}
}
