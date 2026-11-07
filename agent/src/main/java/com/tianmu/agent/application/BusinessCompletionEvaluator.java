package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.runtime.spi.CompletionEvaluator;
import org.springframework.stereotype.Component;
import java.util.*;

/** Feedback report receipts are business facts, never names interpreted by the generic loop. */
@Component
public final class BusinessCompletionEvaluator implements CompletionEvaluator {
    private final Map<String,CompletionOwner> owners;
    public BusinessCompletionEvaluator(){this(List.of(new ReportOwner(),new DraftOwner(),new GeneralOwner()));}
    @org.springframework.beans.factory.annotation.Autowired
    public BusinessCompletionEvaluator(List<CompletionOwner> registered){
        var all=new HashMap<String,CompletionOwner>();
        for(var owner:registered)for(String type:owner.taskTypes())if(all.putIfAbsent(type,owner)!=null)throw new IllegalArgumentException("Multiple completion owners: "+type);
        if(!all.containsKey("*"))throw new IllegalArgumentException("Missing completion fallback");owners=Map.copyOf(all);
    }
    public Decision evaluate(Input input) {
        var pending=input.exchanges().stream().map(e->e.result().pending()).filter(Objects::nonNull).distinct().toList();
        if(pending.size()>1)return new Reject("INVALID_TOOL_RESULT");
        if(!pending.isEmpty())return input.answerProposal()!=null || input.clarificationProposal()!=null ? new Reject("INVALID_TOOL_RESULT") : new Await(pending.get(0));
        if(input.clarificationProposal()!=null)return new Clarify(input.clarificationProposal());
        var selection=ResourceSelection.question(input.task()==null?null:input.task().specification(),input.exchanges().stream().map(ToolExchange::result).toList());
        if(selection.isPresent() && input.answerProposal()!=null)return new Clarify(selection.get());
        String type=input.task()==null?"*":input.task().specification().type();
        return owners.getOrDefault(type,owners.get("*")).evaluate(input);
    }

    @Component
    public static final class GeneralOwner implements CompletionOwner {
        public Set<String> taskTypes(){return Set.of("*");}
        public Decision evaluate(Input input){return input.answerProposal()==null ? new Continue("NO_TERMINAL_PROPOSAL") : new Deliver(input.answerProposal(),input.proposalReason());}
    }
    @Component
    public static final class DraftOwner implements CompletionOwner {
        public Set<String> taskTypes(){return Set.of("creator.draft");}
        public Decision evaluate(Input input){
            if(input.answerProposal()==null)return new Continue("DRAFT_NOT_READY");
            return input.proposalReason()==StopReason.TOOL_DELIVERY ? new Deliver(input.answerProposal(),input.proposalReason()) : new Reject("DRAFT_REQUIRED");
        }
    }
    @Component
    public static final class ReportOwner implements CompletionOwner {
        public Set<String> taskTypes(){return Set.of("audience.report","danmaku.report","comments.analysis");}
        public Decision evaluate(Input input){
            boolean report=input.task()==null || input.task().specification().reading()!=null && input.task().specification().reading().reportRequired();
            if(report){
                var receipts=input.exchanges().stream().map(ToolExchange::result).filter(last->last.tool().equals("create_feedback_report")
                        && "READY".equals(last.data().get("status")) && last.evidenceId()!=null && input.registeredArtifacts().contains(last.evidenceId()) && last.data().containsKey("url")).toList();
                if(!receipts.isEmpty()){
                    var last=receipts.get(receipts.size()-1);
                    return new Deliver(new Answer((Boolean.TRUE.equals(last.data().get("demo"))?"【模拟数据】":"")+"反馈报告已生成并保存。\n[查看 HTML 报告]("+last.data().get("url")+")（需登录鉴权）",List.of(last.evidenceId())),StopReason.TOOL_DELIVERY);
                }
                if(input.answerProposal()!=null && input.proposalReason()!=StopReason.TOOL_DELIVERY)return new Reject("REPORT_REQUIRED");
            }
            return new GeneralOwner().evaluate(input);
        }
    }
}
