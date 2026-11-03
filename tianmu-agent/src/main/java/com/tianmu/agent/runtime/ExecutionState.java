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
    }
    public synchronized void rollbackOperation(com.tianmu.agent.core.PendingOperation.Continuation saved){
        exchanges.clear();observations.clear();activeSkills.clear();restore(saved);
    }
    public synchronized void completeOperation(com.tianmu.agent.core.PendingOperation pending,ToolResult result){
        int index=exchanges.size()-1;if(index<0)throw new IllegalStateException("No pending tool call");var last=exchanges.get(index);
        if(!pending.equals(last.result().pending()) || !last.call().name().equals(result.tool()) || result.pending()!=null || result.terminalAnswer()!=null || result.terminalClarification()!=null)
            throw new IllegalArgumentException("Operation result does not match pending execution");
        exchanges.set(index,new com.tianmu.agent.core.AgentTypes.ToolExchange(last.call(),result));observations.set(index,result);
    }
    public EvidenceStore evidence() { return evidence; }
}
