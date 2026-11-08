package com.tianmu.agent.application;

import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.store.ConversationStore;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Application-owned ordering of durable history, source boundaries, recall and compaction. */
@Component
public final class ContextLifecycle {
    private final ConversationStore conversations;
    private final RuntimeHarness harness;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.MemoryRecallService recall;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.CompactionService compaction;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.MemoryLedger ledger;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.MemoryToolService memories;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.MemoryConsolidation learning;
    public java.util.Optional<com.tianmu.agent.core.AgentTypes.Answer> memoryRequest(RunState run){
        if(learning==null || !com.tianmu.agent.memory.MemoryIntent.save(run.message,run.memory().recent()))return java.util.Optional.empty();
        var receipt=harness.stage(run,"agent.memory.request",null,null,Map.of("memory.trigger_reason","EXPLICIT_REQUEST"),()->learning.requestSave(run));
        if(!com.tianmu.agent.memory.MemoryIntent.exclusive(run.message))return java.util.Optional.empty();
        return java.util.Optional.of(new com.tianmu.agent.core.AgentTypes.Answer("收到，已加入后台记忆整理，会结合前文保存适合长期保留的信息。",java.util.List.of()));
    }
    public boolean memoryManagement(String query){return memories!=null && com.tianmu.agent.memory.MemoryToolService.management(query);}
    public ContextLifecycle(ConversationStore conversations, RuntimeHarness harness) {
        this.conversations=conversations;this.harness=harness;
    }
    public void begin(RunState run) {
        if(recall!=null)recall.begin(run);
        if(compaction!=null)compaction.begin(run);
    }
    public void load(RunState run) {
        if(conversations!=null)run.memory(harness.stage(run,"agent.memory.load",null,null,Map.of(),()->conversations.memory(run)));
        if(ledger!=null)run.memory(run.memory().withRecent(ledger.inherit(run,run.memory().recent())));
        else if(recall!=null)recall.inherit(run);
        if(compaction!=null)compaction.apply(run);
    }
    public void recall(RunState run) { if(recall!=null)recall.recall(run); }
    public void bindFocus(RunState run,com.tianmu.agent.core.TaskTypes.Specification specification) {
        var focus=run.memory().focus();
        if(focus==null)return;
        var updated=new com.tianmu.agent.core.MemoryTypes.Focus(focus.request(),focus.sourceRunId(),focus.referenceVideoId(),specification);
        run.memory(run.memory().withContext(run.memory().environment(),updated));
        if(conversations!=null)conversations.focus(run,updated);
    }
    public void resume(RunState run,boolean direct) {
        if(recall!=null){recall.begin(run);if(direct)recall.verify(run);else recall.restore(run);}
        if(compaction!=null){compaction.begin(run);if(!direct)compaction.apply(run);}
    }
}
