package com.tianmu.agent.memory;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.Answer;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.runtime.RunState;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.util.*;
import static com.tianmu.agent.memory.PersonalMemory.*;

/** Source-grounded host answers. No model tools, inferred identity links or provisional body. */
@Service
@ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
@EnableConfigurationProperties(MemoryRecallPolicy.class)
public final class MemoryHostService {
    private final MemoryLedger ledger;private final MemoryRecallPolicy policy;private final RuntimeHarness harness;
    public MemoryHostService(MemoryLedger ledger,MemoryRecallPolicy policy,RuntimeHarness harness){this.ledger=ledger;this.policy=policy;this.harness=harness;}
    public boolean handles(RunState run){
        var plan=MemoryRecallPlan.of(run.message);
        if(plan.mode()==MemoryRecallPlan.Mode.OVERVIEW)return true;
        return plan.continuation() && run.memory().recent().stream().filter(m->"assistant".equals(m.role())).reduce((a,b)->b)
                .map(m->ledger.recallManifest(run,m.runId()).path("hasMore").asBoolean()).orElse(false);
    }
    public Optional<Answer> answer(RunState run){
        if(MemoryIntent.manageInUi(run.message))return Optional.of(new Answer("请在记忆管理中选择需要修改或删除的条目，确认后立即生效。",List.of()));
        var plan=MemoryRecallPlan.of(run.message);
        var previous=run.memory().recent().stream().filter(m->"assistant".equals(m.role())).reduce((a,b)->b);
        var old=previous.map(m->ledger.recallManifest(run,m.runId())).orElse(null);
        boolean continuation=plan.continuation() && old!=null && old.path("hasMore").asBoolean();
        if(plan.mode()!=MemoryRecallPlan.Mode.OVERVIEW && !continuation)return Optional.empty();
        try{return Optional.of(harness.stage(run,"agent.memory.overview",null,null,Map.of("memory.mode","OVERVIEW","memory.trigger_reason",continuation?"CONTINUE_OVERVIEW":"USER_OVERVIEW"),()->overview(run,plan,continuation?old:null)));}
        catch(AgentException e){if(!e.code().equals("MEMORY_RECALL_TIMEOUT"))throw e;return Optional.of(new Answer("当前读取记忆超时，请稍后重试。",List.of()));}
        catch(org.springframework.dao.TransientDataAccessException e){return Optional.of(new Answer("当前记忆服务暂时不可用，请稍后重试。",List.of()));}
    }
    private Answer overview(RunState run,MemoryRecallPlan plan,com.fasterxml.jackson.databind.JsonNode previous){
        long deadline=System.nanoTime()+policy.timeoutMillis()*1_000_000L;
        var b=ledger.boundary(run.owner,run.id);if(b==null)throw MemoryLedger.invalidated();
        if(b.mode()==Mode.OFF){TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.status","DISABLED"));return new Answer("助手记忆目前已关闭。你可以在记忆管理中开启后再查看。",List.of());}
        long before=Long.MAX_VALUE;String after="",kind=plan.kind();boolean reset=false;
        if(previous!=null){
            if(previous.path("sequence").asLong()!=b.sequence() || previous.path("consent").asLong()!=b.consent() || previous.path("erase").asLong()!=b.erase())reset=true;
            else {before=previous.path("nextSequence").asLong();after=previous.path("nextId").asText();kind=previous.path("kind").asText();}
        }
        var cached=ledger.recallManifest(run,run.id);
        if(!cached.isEmpty())return render(ledger.restoreInitial(run),cached.path("hasMore").asBoolean(),cached.path("reset").asBoolean(),cached.path("kind").asText());
        var candidates=ledger.overview(run.owner,b.sequence(),kind,before,after,policy.overviewScanLimit()+1);
        var accepted=new ArrayList<Entry>();var roots=new HashSet<String>();b.refs().forEach(r->roots.add(r.id()+":"+r.version()));
        int bytes=0,characters=0;boolean more=false;
        for(var entry:candidates){
            run.checkpoint();int size=entry.content().text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String root=entry.id()+":"+entry.version();
            int chars=plain(entry.content().text()).length()+4;
            if(accepted.size()>=policy.overviewMaxItems() || bytes+size>policy.overviewMaxBytes() || characters+chars>7600 || !roots.contains(root) && roots.size()>=32){more=true;break;}
            accepted.add(entry);bytes+=size;characters+=chars;roots.add(root);before=entry.sequence();after=entry.id().toString();
        }
        var manifest=new LinkedHashMap<String,Object>();manifest.put("version","host-overview-v1");manifest.put("sequence",b.sequence());manifest.put("consent",b.consent());manifest.put("erase",b.erase());manifest.put("kind",kind);manifest.put("hasMore",more);manifest.put("complete",!more);manifest.put("nextSequence",before);manifest.put("nextId",after);manifest.put("reset",reset);manifest.put("selected",accepted.size());
        manifest.put("status",more?"PARTIAL":accepted.isEmpty()?"EMPTY":"READY");manifest.put("deadlineNano",deadline);ledger.commitOverview(run,accepted,manifest);manifest.remove("deadlineNano");run.memoryPacketReady(true);
        TraceDetails.output(manifest);TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("memory.selected_count",accepted.size(),"memory.candidate_count",candidates.size(),"memory.context_bytes",bytes,"memory.coverage_complete",!more,"memory.has_more",more,"memory.snapshot_seq",b.sequence(),"memory.snapshot_changed",reset));
        return render(accepted,more,reset,kind);
    }
    static Answer render(List<Entry> entries,boolean more,boolean reset,String kind){
        StringBuilder text=new StringBuilder();if(reset)text.append("记忆已发生变化，我重新整理了当前可见的信息。\n\n");
        if(entries.isEmpty())text.append(more?"本轮可读取的记忆额度已用完，请新开会话查看更多。":"目前没有查到"+(kind.isEmpty()?"可用的个人记忆。":"这类可用的个人记忆。"));
        else {text.append("目前查到这些关于你的记录：\n");for(var e:entries)text.append("\n- ").append(plain(e.content().text()));}
        if(more && !entries.isEmpty())text.append("\n\n还有更多记录，你可以说“继续查看”。");
        return new Answer(text.toString(),List.of());
    }
    private static String plain(String value){return value.replace("\\","\\\\").replace("\n"," ").replace("\r"," ").replace("<","&lt;").replace(">","&gt;").replace("[","\\[").replace("]","\\]").replace("*","\\*").replace("_","\\_").replace("`","\\`");}
}
