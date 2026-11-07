package com.tianmu.agent.interaction;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import java.util.*;

/** Shared draft admission. Comment and timed danmaku tools have distinct schemas and the same publication boundary. */
public final class CreatorDraftPreparation {
    private final CreatorDraftStore drafts;
    public CreatorDraftPreparation(CreatorDraftStore drafts){this.drafts=drafts;}
    public ToolResult prepare(String tool,String kind,ToolCall call,ToolContext context){
        long videoId=ToolArguments.integer(call,"video_id");var evidence=context.evidence(ToolArguments.text(call,"video_evidence_id"));
        if(evidence.videoContent()==null || evidence.videoContent().videoId()!=videoId)throw new AgentException("INVALID_EVIDENCE","Read this video's content first");
        context.checkVideoQuery(videoId,evidence.videoContent().startMs(),evidence.videoContent().endMs());context.checkCreatorDraft(videoId,kind);
        Evidence feedback=call.arguments().containsKey("feedback_evidence_id")?context.evidence(ToolArguments.text(call,"feedback_evidence_id")):null;
        var contract=context.analysisContract();boolean required=contract!=null && !contract.textSources().isEmpty();
        if(required && feedback==null)throw new AgentException("INVALID_EVIDENCE","This draft requires the frozen feedback analysis");
        if(feedback!=null){
            if(feedback.feedback()==null || feedback.feedback().request().query().videoId()!=videoId || !required)
                throw new AgentException("TASK_CONSTRAINT_VIOLATION","Feedback is not selected for this draft");
            context.checkFeedback(feedback.feedback().request());
        }
        List<CreatorDraft.Item> items=new ArrayList<>();Map<String,String> references=new LinkedHashMap<>();
        for(Object raw:(List<?>)call.arguments().get("items")){
            Map<?,?> item=(Map<?,?>)raw;Long at=item.containsKey("playback_ms")?new java.math.BigDecimal(item.get("playback_ms").toString()).longValueExact():null;
            if(at!=null && (at<evidence.videoContent().startMs() || at>=evidence.videoContent().endMs()))throw new AgentException("INVALID_DRAFT","Danmaku position must be inside the analyzed playback interval");
            String id;do{id=Long.toString(UUID.randomUUID().getMostSignificantBits()&Long.MAX_VALUE);}while(id.equals("0"));
            if(required){
                Object topic=item.get("feedback_topic_id");if(!(topic instanceof String ref) || ref.isBlank())throw new AgentException("INVALID_DRAFT","Feedback-driven drafts require an item-level topic reference");
                context.checkFeedbackTopic(feedback,ref);references.put(id,ref);
            }else if(item.containsKey("feedback_topic_id"))throw new AgentException("INVALID_DRAFT","No feedback evidence selected");
            items.add(new CreatorDraft.Item(id,(String)item.get("text"),at));
        }
        CreatorDraft.validate(kind,items);var scope=context.scope();
        var basis=new CreatorDraft.Basis(evidence.id(),feedback==null?null:feedback.id(),references);
        var draft=new CreatorDraft(context.runId(),scope.tenantId(),scope.userId(),Long.toString(videoId),kind,"DRAFT",items,basis);
        context.checkpoint();drafts.create(draft);var ids=new ArrayList<String>();ids.add(evidence.id());if(feedback!=null)ids.add(feedback.id());
        return new ToolResult(tool,Map.of("draft_ready",true,"count",items.size(),"kind",kind),evidence.id(),new Answer("已生成"+items.size()+"条"+(kind.equals("COMMENT")?"评论":"弹幕")+"草稿。请在下方预览、修改并勾选，确认后才会用你的作者账号发送。",ids));
    }
}
