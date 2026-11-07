package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentTypes.ToolResult;
import com.tianmu.agent.runtime.store.*;
import java.util.*;

/** Host-owned mutable execution data. Tools receive a restricted facade instead. */
public final class ExecutionState {
    private final Set<String> activeSkills = new HashSet<>();
    private final List<ToolResult> observations = new ArrayList<>();
    private final List<com.tianmu.agent.core.AgentTypes.ToolExchange> exchanges = new ArrayList<>();
    private final EvidenceStore evidence;
    private final Set<String> readyReports = new HashSet<>();
    public synchronized void reportReady(String evidenceId) { readyReports.add(evidenceId); }
    public synchronized boolean hasReport(String evidenceId) { return readyReports.contains(evidenceId); }
    public synchronized Set<String> artifactEvidence(){return Set.copyOf(readyReports);}
    public ExecutionState() { this(new InMemoryEvidenceStore()); }
    public ExecutionState(EvidenceStore evidence) { this.evidence = evidence; }
    public synchronized Set<String> activeSkills() { return Set.copyOf(activeSkills); }
    public synchronized void activateSkill(String name) { activeSkills.add(name); }
    public synchronized List<ToolResult> observations() { return List.copyOf(observations); }
    public synchronized void observe(ToolResult value) { observations.add(value); }
    public synchronized void observe(com.tianmu.agent.core.AgentTypes.ToolCall call,ToolResult value) {
        observations.add(value);exchanges.add(new com.tianmu.agent.core.AgentTypes.ToolExchange(call,value));
    }
    public synchronized List<com.tianmu.agent.core.AgentTypes.ToolExchange> exchanges() { return List.copyOf(exchanges); }
    public synchronized void restore(com.tianmu.agent.core.PendingOperation.Continuation continuation){
        if(!exchanges.isEmpty())throw new IllegalStateException("Execution was already restored");
        activeSkills.addAll(continuation.skills());exchanges.addAll(continuation.exchanges());continuation.exchanges().forEach(e->observations.add(e.result()));
        continuation.exchanges().forEach(e->restoreReport(e.result()));
    }
    public synchronized void rollbackOperation(com.tianmu.agent.core.PendingOperation.Continuation saved){
        exchanges.clear();observations.clear();activeSkills.clear();readyReports.clear();restore(saved);
    }
    public synchronized void completeOperation(com.tianmu.agent.core.PendingOperation pending,ToolResult result){
        completeOperations(pending,List.of(result));
    }
    public synchronized void completeOperations(com.tianmu.agent.core.PendingOperation pending,List<ToolResult> results){
        var matching=new LinkedHashMap<String,Integer>();
        for(int i=0;i<exchanges.size();i++)if(pending.equals(exchanges.get(i).result().pending())){
            if(matching.put(exchanges.get(i).call().name(),i)!=null)throw new IllegalStateException("Repeated pending tool name");
        }
        if(results.size()!=matching.size() || results.stream().map(ToolResult::tool).distinct().count()!=results.size())throw new IllegalArgumentException("Incomplete operation group");
        for(var result:results)if(!matching.containsKey(result.tool()) || result.pending()!=null || result.terminalAnswer()!=null || result.terminalClarification()!=null)throw new IllegalArgumentException("Invalid operation group result");
        for(var result:results){int index=matching.get(result.tool());var old=exchanges.get(index);exchanges.set(index,new com.tianmu.agent.core.AgentTypes.ToolExchange(old.call(),result));observations.set(index,result);restoreReport(result);}
    }
    private void restoreReport(ToolResult result){if("READY".equals(result.data().get("status")) && result.data().containsKey("artifact_id") && result.evidenceId()!=null)readyReports.add(result.evidenceId());}
    public EvidenceStore evidence() { return evidence; }
}
