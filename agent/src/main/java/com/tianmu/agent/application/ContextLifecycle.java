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
    public void resume(RunState run,boolean direct) {
        if(recall!=null){recall.begin(run);if(direct)recall.verify(run);else recall.restore(run);}
        if(compaction!=null){compaction.begin(run);if(!direct)compaction.apply(run);}
    }
}
