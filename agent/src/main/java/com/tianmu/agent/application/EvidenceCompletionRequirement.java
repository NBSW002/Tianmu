package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.spi.CompletionEvaluator;
import java.util.*;
import org.springframework.stereotype.Component;

/** Completeness uses host evidence and recorded reading; Delivery repeats live authorization. */
@Component
public final class EvidenceCompletionRequirement implements CompletionRequirement {
    public String name(){return "task-evidence-and-reading";}
    public void verify(CompletionEvaluator.Input input,CompletionEvaluator.Deliver proposal) {
        if(input.task()==null)return;
        var task=input.task();var answer=proposal.draft();
        if(answer==null || answer.text()==null || answer.text().isBlank() || answer.text().length()>8000 || answer.evidenceIds().size()>10
                || !"conversation".equals(task.specification().type()) && answer.evidenceIds().isEmpty())throw new AgentException("INVALID_ANSWER","A bounded answer with evidence is required");
        if(ResourceSelection.requiresEvidence(task.specification(),input.exchanges().stream().map(ToolExchange::result).toList()))throw new AgentException("INVALID_EVIDENCE","Discovery requires a host receipt");
        var selected=new ArrayList<Evidence>();
        for(String id:answer.evidenceIds()) {
            var item=input.evidence().get(id);
            if(item==null || !item.runId().equals(task.runId()) || !item.owner().equals(task.scope()))throw new AgentException("INVALID_EVIDENCE","Completion evidence is unavailable");
            selected.add(item);
        }
        AnalysisExecutionPolicy.requireComplete(task.specification(),selected);
        AnalysisExecutionPolicy.requireTopicsRead(task.specification(),selected,input.exchanges());
    }
}
