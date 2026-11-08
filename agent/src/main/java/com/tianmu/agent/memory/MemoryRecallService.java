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
    @org.springframework.beans.factory.annotation.Autowired(required=false) private MemoryPolicyProperties policy=MemoryPolicyProperties.defaults();
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
            String query=run.message;
            // Previous USER text only supplements a short referential request.
            if(query.codePointCount(0,query.length())<=12 && query.matches("(?s).*(这个|那个|之前|刚才|继续|学校|那里).*")){
                var recent=run.memory().recent().stream().filter(m->"user".equalsIgnoreCase(m.role())).toList();
                query+=" " + recent.stream().skip(Math.max(0,recent.size()-policy.queryHistoryMessages())).map(MemoryTypes.Message::text).collect(java.util.stream.Collectors.joining(" "));
            }
            query=MemorySelection.expand(query);
            var core=ledger.core(run.owner,boundary.sequence(),governance.coreCandidates());
            for(var entry:ledger.candidates(run.owner,Scope.user(),boundary.sequence(),query,governance.queryCandidates()))selected.put(entry.id(),entry);
            if(run.pageContext()!=null)for(var entry:ledger.candidates(run.owner,new Scope(ScopeType.VIDEO,run.pageContext().videoId()),boundary.sequence(),"",governance.scopedCandidates()))selected.put(entry.id(),entry);
            for(var topic:ledger.topics(run.owner)) {
                String name=topic.get("name").toString();
                if(!Collections.disjoint(MemorySelection.terms(name),MemorySelection.terms(query)))
                    for(var entry:ledger.candidates(run.owner,new Scope(ScopeType.TOPIC,topic.get("id").toString()),boundary.sequence(),"",governance.scopedCandidates()))selected.put(entry.id(),entry);
            }
            var ranked=MemorySelection.rank(selected.values(),query,run.pageContext()==null?null:run.pageContext().videoId());
            var accepted=new ArrayList<Entry>();int bytes=0,coreBytes=0,coreCount=0;var roots=new HashSet<String>();boundary.refs().forEach(ref->roots.add(ref.id()+":"+ref.version()));
            var reasons=new ArrayList<Map<String,Object>>();var queue=new ArrayList<Entry>(core);queue.addAll(ranked);var seen=new HashSet<UUID>();var texts=new HashSet<String>();
            for(var entry:queue) {
                if(!seen.add(entry.id()))continue;
                boolean isCore=core.stream().anyMatch(e->e.id().equals(entry.id()));
                String reason="SELECTED",root=entry.id()+":"+entry.version();int size;
                try{size=json.writeValueAsBytes(packet(run,entry)).length;}catch(java.io.IOException e){throw new AgentException("STORAGE_CORRUPT","Invalid memory projection");}
                int coreLimit=Math.min(policy.coreMaxItems(),Math.max(0,governance.maxRecallItems()-(ranked.isEmpty()?0:1)));
                int coreByteLimit=Math.min(policy.coreMaxBytes(),ranked.isEmpty()?governance.maxRecallBytes():governance.maxRecallBytes()/2);
                if(isCore && (coreCount>=coreLimit || coreBytes+size>coreByteLimit)){
                    if(MemorySelection.score(entry,query,run.pageContext()==null?null:run.pageContext().videoId())>0)isCore=false;
                    else reason="CORE_BUDGET";
                }
                if(reason.equals("SELECTED") && (accepted.size()>=governance.maxRecallItems() || bytes+size>governance.maxRecallBytes() || !roots.contains(root) && roots.size()>=32))reason="CONTEXT_BUDGET";
                else if(reason.equals("SELECTED") && !texts.add(MemoryConsolidation.normalize(entry.content().text())))reason="DUPLICATE";
                reasons.add(Map.of("id",entry.id(),"version",entry.version(),"layer",isCore?"CORE":"RELATED","reason",reason));
                if(!reason.equals("SELECTED"))continue;
                bytes+=size;if(isCore){coreBytes+=size;coreCount++;}roots.add(root);accepted.add(entry);
            }
            for(var entry:selected.values())if(!seen.contains(entry.id()))reasons.add(Map.of("id",entry.id(),"version",entry.version(),"layer","RELATED","reason","NOT_RELEVANT"));
            TraceDetails.output(Map.of("selection",reasons,"coreCount",coreCount,"relatedCount",accepted.size()-coreCount));
            var committed=ledger.commitRecall(run,accepted);
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.candidate_count",java.util.stream.Stream.concat(core.stream(),selected.values().stream()).map(Entry::id).distinct().count(),"memory.selected_count",committed.size(),"memory.context_bytes",bytes,"memory.snapshot_seq",boundary.sequence(),"memory.reused",false,"memory.core_count",coreCount,"memory.related_count",accepted.size()-coreCount));
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
