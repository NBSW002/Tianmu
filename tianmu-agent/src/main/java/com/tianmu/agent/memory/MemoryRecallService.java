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
public final class MemoryRecallService {
    private final MemoryLedger ledger;
    private final PersonalMemoryStore store;
    private final RuntimeHarness harness;
    private final ObjectMapper json;
    public MemoryRecallService(MemoryLedger ledger,PersonalMemoryStore store,RuntimeHarness harness,ObjectMapper json){this.ledger=ledger;this.store=store;this.harness=harness;this.json=json;}
    public void begin(RunState run){
        // Protection starts before user input capture: a future explicit source must not acquire
        // an untracked duplicate in a provider-request or preparation Trace object.
        TraceDetails.protectMemory();
        harness.stage(run,"agent.memory.snapshot",null,null,Map.of(),()->{ledger.begin(run);return Map.of("snapshot",run.id.toString());});
        var nextCheck=new java.util.concurrent.atomic.AtomicLong();
        run.memoryGuard(()->{long now=System.nanoTime(),next=nextCheck.get();if(now>=next && nextCheck.compareAndSet(next,now+100_000_000L))ledger.guard(run);},()->ledger.guard(run));
    }
    public void inherit(RunState run){run.memory(run.memory().withRecent(ledger.inherit(run,run.memory().recent())));}
    public void verify(RunState run){ledger.guard(run);}
    public void recall(RunState run){
        List<Entry> entries=harness.stage(run,"agent.memory.recall",null,null,Map.of(),()->{
            var boundary=ledger.boundary(run.owner,run.id);
            if(boundary==null || boundary.mode()==Mode.OFF || store.settings(run.owner).mode()==Mode.OFF)return List.<Entry>of();
            if(boundary.recalled()) {ledger.guard(run);return boundary.refs().stream().filter(MemoryLedger.Ref::initial).map(ref->ledger.read(run.owner,ref.id(),ref.version(),ref.sequence()==0?boundary.sequence():ref.sequence())).toList();}
            var selected=new LinkedHashMap<UUID,Entry>();
            // Core preferences and matching facts are separate candidates, with one final byte budget.
            for(var entry:ledger.candidates(run.owner,Scope.user(),boundary.sequence(),"",6))if(entry.content().kind()==Kind.PREFERENCE)selected.put(entry.id(),entry);
            for(var entry:ledger.candidates(run.owner,Scope.user(),boundary.sequence(),run.message,16))selected.put(entry.id(),entry);
            if(run.pageContext()!=null)for(var entry:ledger.candidates(run.owner,new Scope(ScopeType.VIDEO,run.pageContext().videoId()),boundary.sequence(),"",12))selected.put(entry.id(),entry);
            for(var topic:ledger.topics(run.owner)) {
                String name=topic.get("name").toString();
                if(!Collections.disjoint(MemoryKeywords.terms(name,16),MemoryKeywords.terms(run.message,16)))
                    for(var entry:ledger.candidates(run.owner,new Scope(ScopeType.TOPIC,topic.get("id").toString()),boundary.sequence(),"",12))selected.put(entry.id(),entry);
            }
            var accepted=new ArrayList<Entry>();int bytes=0;var roots=new HashSet<String>();boundary.refs().forEach(ref->roots.add(ref.id()+":"+ref.version()));
            for(var entry:selected.values()) {
                String root=entry.id()+":"+entry.version();if(!roots.contains(root) && roots.size()>=32)continue;
                try{int size=json.writeValueAsBytes(packet(run,entry)).length;if(bytes+size>6144)continue;bytes+=size;accepted.add(entry);}catch(java.io.IOException e){throw new AgentException("STORAGE_CORRUPT","Invalid memory projection");}
                roots.add(root);
                if(accepted.size()==12)break;
            }
            ledger.consume(run,accepted,true);ledger.recalled(run);
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.candidate_count",selected.size(),"memory.selected_count",accepted.size(),"memory.context_bytes",bytes,"memory.snapshot_seq",boundary.sequence()));
            return List.copyOf(accepted);
        });
        run.memory(run.memory().withPersistent(entries.stream().map(entry->packet(run,entry)).toList()));
    }
    private MemoryTypes.Persistent packet(RunState run,Entry entry){var c=entry.content();return new MemoryTypes.Persistent(entry.id(),entry.version(),c.scope().type().name(),c.scope().key(),c.kind().name(),c.text(),ledger.sourceRefs(run.owner,entry),c.expiresAt());}
}
