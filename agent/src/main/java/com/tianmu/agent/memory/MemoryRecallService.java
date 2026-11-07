package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.harness.trace.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;

/** Independent recall node. Its only model-visible product is the memory_context module. */
@Service
@ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
public final class MemoryRecallService implements com.tianmu.agent.runtime.spi.ContextAuthority {
    private final com.tianmu.agent.memory.MemoryRuntimeProperties governance;
    private final MemoryLedger ledger;
    private final PersonalMemoryStore store;
    private final RuntimeHarness harness;
    private final ObjectMapper json;
    public MemoryRecallService(MemoryLedger ledger,PersonalMemoryStore store,RuntimeHarness harness,ObjectMapper json) { this(ledger, store, harness, json, com.tianmu.agent.memory.MemoryRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public MemoryRecallService(MemoryLedger ledger,PersonalMemoryStore store,RuntimeHarness harness,ObjectMapper json, com.tianmu.agent.memory.MemoryRuntimeProperties governance) { this.governance=governance;this.ledger=ledger;this.store=store;this.harness=harness;this.json=json;}
    public void begin(RunState run){
        // Protection starts before user input capture: a future explicit source must not acquire
        // an untracked duplicate in a provider-request or preparation Trace object.
        TraceDetails.protectMemory();
        if(run.memoryBoundaryReady())return;
        if(ledger.boundary(run.owner,run.id)==null)
            harness.stage(run,"agent.memory.snapshot",null,null,Map.of(),()->{ledger.begin(run);return Map.of("snapshot",run.id.toString());});
        var nextCheck=new java.util.concurrent.atomic.AtomicLong();
        run.memoryGuard(()->{long now=System.nanoTime(),next=nextCheck.get();if(now>=next && nextCheck.compareAndSet(next,now+governance.guardIntervalMillis()*1_000_000L))ledger.guard(run);},()->ledger.guard(run));
        run.memoryBoundaryReady(true);
    }
    public void inherit(RunState run){run.memory(run.memory().withRecent(ledger.inherit(run,run.memory().recent())));}
    public void verify(RunState run){ledger.guard(run);}
    public void recall(RunState run){
        if(run.memoryPacketReady()){ledger.guard(run);return;}
        var existing=ledger.boundary(run.owner,run.id);
        if(existing!=null && existing.recalled()){restore(run);return;}
        List<Entry> entries=harness.stage(run,"agent.memory.recall",null,null,Map.of(),()->{
            var boundary=ledger.boundary(run.owner,run.id);
            if(boundary==null)throw MemoryLedger.invalidated();
            if(boundary.mode()==Mode.OFF)return ledger.commitRecall(run,List.of());
            var selected=new LinkedHashMap<UUID,Entry>();
            // Core preferences and matching facts are separate candidates, with one final byte budget.
            for(var entry:ledger.candidates(run.owner,Scope.user(),boundary.sequence(),"",governance.coreCandidates()))if(entry.content().kind()==Kind.PREFERENCE)selected.put(entry.id(),entry);
            for(var entry:ledger.candidates(run.owner,Scope.user(),boundary.sequence(),run.message,governance.queryCandidates()))selected.put(entry.id(),entry);
            if(run.pageContext()!=null)for(var entry:ledger.candidates(run.owner,new Scope(ScopeType.VIDEO,run.pageContext().videoId()),boundary.sequence(),"",governance.scopedCandidates()))selected.put(entry.id(),entry);
            for(var topic:ledger.topics(run.owner)) {
                String name=topic.get("name").toString();
                if(!Collections.disjoint(MemoryKeywords.terms(name,16),MemoryKeywords.terms(run.message,16)))
                    for(var entry:ledger.candidates(run.owner,new Scope(ScopeType.TOPIC,topic.get("id").toString()),boundary.sequence(),"",governance.scopedCandidates()))selected.put(entry.id(),entry);
            }
            var accepted=new ArrayList<Entry>();int bytes=0;var roots=new HashSet<String>();boundary.refs().forEach(ref->roots.add(ref.id()+":"+ref.version()));
            for(var entry:selected.values()) {
                String root=entry.id()+":"+entry.version();if(!roots.contains(root) && roots.size()>=32)continue;
                try{int size=json.writeValueAsBytes(packet(run,entry)).length;if(bytes+size>governance.maxRecallBytes())continue;bytes+=size;accepted.add(entry);}catch(java.io.IOException e){throw new AgentException("STORAGE_CORRUPT","Invalid memory projection");}
                roots.add(root);
                if(accepted.size()==governance.maxRecallItems())break;
            }
            var committed=ledger.commitRecall(run,accepted);
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.candidate_count",selected.size(),"memory.selected_count",committed.size(),"memory.context_bytes",bytes,"memory.snapshot_seq",boundary.sequence(),"memory.reused",false));
            return committed;
        });
        run.memory(run.memory().withPersistent(entries.stream().map(entry->packet(run,entry)).toList()));
        run.memoryPacketReady(true);
    }
    public void restore(RunState run){
        ledger.guard(run);
        if(run.memoryPacketReady())return;
        var entries=harness.stage(run,"agent.memory.restore",null,null,Map.of("memory.reused",true,"memory.restore_reason","PROCESS_OR_PACKET_RECOVERY"),()->{
            var restored=ledger.restoreInitial(run);
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.selected_count",restored.size()));return restored;
        });
        run.memory(run.memory().withPersistent(entries.stream().map(e->packet(run,e)).toList()));run.memoryPacketReady(true);
    }
    private MemoryTypes.Persistent packet(RunState run,Entry entry){var c=entry.content();return new MemoryTypes.Persistent(entry.id(),entry.version(),c.scope().type().name(),c.scope().key(),c.kind().name(),c.text(),ledger.sourceRefs(run.owner,entry),c.expiresAt());}
}
