package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;

@Component
public final class CreatorInteractionTask implements TaskSpecificationResolver, QueryReadinessPolicy {
    private static final String KINDS="(?:智能)?(?:评论|弹幕)(?:\\s*(?:和|与|及|、|/|以及)\\s*(?:智能)?(?:评论|弹幕))*";
    private static final java.util.regex.Pattern TARGETS=java.util.regex.Pattern.compile(
            "(?:生成|写|拟|起草|发送|发布)[^，,。；;！？!?]{0,24}?("+KINDS+")|("+KINDS+"\\s*草稿)|(智能(?:评论|弹幕))");
    private static final java.util.regex.Pattern FEEDBACK_CLAUSES=java.util.regex.Pattern.compile(
            "(?s)(?:根据|结合|参考|基于|按照).*?(?=(?:生成|写|拟|起草|发送|发布)|[，,。；;！？!?]|$)");
    public static boolean requested(String text) {
        if ((text.contains("报告") || text.contains("复盘")) && !text.contains("草稿") && !text.contains("智能评论") && !text.contains("智能弹幕")) return false;
        return text.matches("(?s).*(智能评论|智能弹幕|(?:生成|写|拟|起草|发送|发布).{0,20}(?:评论|弹幕)|(?:评论|弹幕).{0,12}草稿).*");
    }
    public static Specification parse(String text) {
        return parse(text,java.time.LocalDate.now(com.tianmu.agent.core.DanmakuTypes.ZONE),java.time.ZonedDateTime.now(com.tianmu.agent.core.DanmakuTypes.ZONE));
    }
    public static Specification parse(String text,LocalDate date,java.time.ZonedDateTime requestedAt) {
        // Remove source clauses before interpreting output nouns; source order must not change the target kind.
        var clauses=FEEDBACK_CLAUSES.matcher(text);var output=new StringBuffer();var feedbackParts=new ArrayList<String>();
        while(clauses.find()){feedbackParts.add(clauses.group());clauses.appendReplacement(output,"");}
        clauses.appendTail(output);String feedbackText=String.join("，",feedbackParts);
        var targets=TARGETS.matcher(output);Set<String> kinds=new HashSet<>();
        while(targets.find()){
            String phrase=targets.group(1)!=null?targets.group(1):targets.group(2)!=null?targets.group(2):targets.group(3);
            java.util.regex.Pattern.compile("评论|弹幕").matcher(phrase).results().forEach(m->kinds.add(m.group()));
        }
        if(kinds.isEmpty()){if(output.indexOf("评论")>=0)kinds.add("评论");if(output.indexOf("弹幕")>=0)kinds.add("弹幕");}
        if(kinds.size()!=1)throw new IllegalArgumentException("请先选择生成评论还是弹幕草稿。");
        var video = VideoContentTask.parse(text);
        Map<String,Object> conditions = new HashMap<>(video.conditions());
        conditions.put("kind", kinds.contains("弹幕") ? "DANMAKU" : "COMMENT");
        Map<String,String> provenance = new HashMap<>(video.provenance()); provenance.put("kind", "user_input");
        boolean feedback=feedbackText.matches("(?s).*(?:根据|结合|参考|基于|按照).*(?:反馈|评论|弹幕|观众问题|观众提问).*");
        if(!feedback)return new Specification("creator.draft", conditions, provenance);
        var query=DanmakuReportTask.parse(text,date,requestedAt);query.checkTime(requestedAt.toInstant());
        conditions.put("feedback_start",query.start().toString());conditions.put("feedback_end",query.end().toString());
        provenance.put("feedback_start","server_parsed");provenance.put("feedback_end","server_parsed");
        var sources=java.util.EnumSet.of(com.tianmu.agent.core.AnalysisContract.Source.VIDEO);
        if(feedbackText.contains("评论"))sources.add(com.tianmu.agent.core.AnalysisContract.Source.COMMENT);
        if(feedbackText.contains("弹幕"))sources.add(com.tianmu.agent.core.AnalysisContract.Source.DANMAKU);
        if(sources.size()==1)sources.addAll(Set.of(com.tianmu.agent.core.AnalysisContract.Source.COMMENT,com.tianmu.agent.core.AnalysisContract.Source.DANMAKU));
        var bounds=(com.tianmu.agent.core.TaskConditions.Video)video.typed();
        return new Specification("creator.draft",conditions,provenance,new com.tianmu.agent.core.AnalysisContract(sources,false,bounds.startMs(),bounds.endMs()));
    }
    public Optional<Specification> resolve(String text, LocalDate date) {
        if (!requested(text)) return Optional.empty();
        try { return Optional.of(parse(text,date,date.atStartOfDay(com.tianmu.agent.core.DanmakuTypes.ZONE))); } catch (IllegalArgumentException e) { return Optional.empty(); }
    }
    public Optional<Specification> resolve(String text,LocalDate date,java.time.Instant requestedAt){
        if(!requested(text))return Optional.empty();try{return Optional.of(parse(text,date,requestedAt.atZone(com.tianmu.agent.core.DanmakuTypes.ZONE)));}catch(IllegalArgumentException e){return Optional.empty();}
    }
    public Optional<com.tianmu.agent.core.PreparationTypes.Clarification> validate(com.tianmu.agent.core.PreparationTypes.Input input) {
        if (!requested(input.targetedQuery())) return Optional.empty();
        try { parse(input.targetedQuery(),input.businessDate(),input.requestedAt().atZone(com.tianmu.agent.core.DanmakuTypes.ZONE)); return Optional.empty(); }
        catch (IllegalArgumentException e) { return Optional.of(new com.tianmu.agent.core.PreparationTypes.Clarification(true, e.getMessage())); }
    }
}
