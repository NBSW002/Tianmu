package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class TaskMeaning {
    private final List<TaskSpecificationResolver> resolvers;
    public TaskMeaning(List<TaskSpecificationResolver> resolvers) { this.resolvers = List.copyOf(resolvers); }
    public static TaskMeaning defaults() { return new TaskMeaning(List.of(new MetricTaskSpecificationResolver(), new DanmakuReportSpecificationResolver(), new VideoContentSpecificationResolver(), new CommentAnalysisSpecificationResolver(), new AudienceReportSpecificationResolver(), new CreatorInteractionTask())); }
    public Specification resolve(String text, LocalDate date) {
        return resolve(text, date, date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
    }
    public Specification resolve(String text, LocalDate date, java.time.Instant requestedAt) {
        List<Specification> matches = resolvers.stream().map(r -> r.resolve(text, date, requestedAt)).flatMap(Optional::stream).toList();
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous task specification resolvers");
        if (matches.isEmpty()) {
            if (com.tianmu.agent.core.DanmakuTarget.matcher(text).find() && AnalysisSourceSelection.mentions(text)
                    && !com.tianmu.agent.core.ConversationTopics.general(text)
                    && text.matches("(?s).*(?:只看|只读|仅看|单看|不看|不要|不用|排除|读取).*"))
                throw new com.tianmu.agent.core.AgentException("AMBIGUOUS_ANALYSIS_SOURCES", "请明确本次要分析视频内容、评论、弹幕中的哪些来源。");
            return new Specification("unclassified", Map.of(), Map.of());
        }
        Specification spec = matches.get(0);
        var baseline = com.tianmu.agent.core.AnalysisContract.legacy(spec.type());
        var reading = "creator.draft".equals(spec.type()) ? spec.analysis()==null?baseline:spec.analysis() : AnalysisSourceSelection.select(text, baseline);
        reading = refresh(text,playback(text, spec, reading));
        return selected(spec,reading,date,requestedAt);
    }
    private com.tianmu.agent.core.AnalysisContract refresh(String text,com.tianmu.agent.core.AnalysisContract reading){
        if(reading==null)return null;
        boolean force=text.matches("(?s).*(?:重新分析|重做分析|绕过分析缓存|强制刷新分析).*")
                && !text.matches("(?s).*(?:不要|不用|无需|不必|别)(?:再)?重新分析.*");
        return new com.tianmu.agent.core.AnalysisContract(reading.sources(),reading.reportRequired(),reading.videoStartMs(),reading.videoEndMs(),force);
    }
    private com.tianmu.agent.core.AnalysisContract playback(String text, Specification spec, com.tianmu.agent.core.AnalysisContract reading) {
        if(reading==null || !reading.includes(com.tianmu.agent.core.AnalysisContract.Source.VIDEO) || "creator.draft".equals(spec.type()))return reading;
        var typed=spec.typed();
        long id=typed instanceof com.tianmu.agent.core.TaskConditions.Video v?v.videoId():typed instanceof com.tianmu.agent.core.TaskConditions.Report r?r.query().videoId():typed instanceof com.tianmu.agent.core.TaskConditions.Metric m?m.videoId():0;
        if(id==0)return reading;
        try {
            var bounds=VideoContentTask.playbackBounds(text,id);
            return bounds.isEmpty()?reading:new com.tianmu.agent.core.AnalysisContract(reading.sources(),reading.reportRequired(),bounds.get().startMs(),bounds.get().endMs());
        } catch(IllegalArgumentException invalid) {
            throw new com.tianmu.agent.core.AgentException("AMBIGUOUS_ANALYSIS_SOURCES",invalid.getMessage());
        }
    }
    private Specification selected(Specification base,com.tianmu.agent.core.AnalysisContract reading,LocalDate date,java.time.Instant at) {
        if(reading==null || "creator.draft".equals(base.type()))return new Specification(base.type(),base.conditions(),base.provenance(),reading);
        var typed=base.typed();
        if(typed instanceof com.tianmu.agent.core.TaskConditions.Video video && reading.videoStartMs()==null)
            reading=new com.tianmu.agent.core.AnalysisContract(reading.sources(),reading.reportRequired(),video.startMs(),video.endMs());
        long id=typed instanceof com.tianmu.agent.core.TaskConditions.Video v?v.videoId():typed instanceof com.tianmu.agent.core.TaskConditions.Metric m?m.videoId():typed instanceof com.tianmu.agent.core.TaskConditions.Report r?r.query().videoId():0;
        if(id==0)return new Specification(base.type(),base.conditions(),base.provenance(),reading);
        var texts=reading.textSources();
        String type;
        // Keep dormant feedback conditions when a follow-up temporarily selects video only.
        // Switching sources back must restore the same collection range, not parse a new date.
        if(texts.isEmpty())type=typed instanceof com.tianmu.agent.core.TaskConditions.Report || typed instanceof com.tianmu.agent.core.TaskConditions.Metric?base.type():"video.content";
        else if(texts.contains(com.tianmu.agent.core.AnalysisContract.Source.COMMENT))type=reading.reportRequired() || texts.size()==2?"audience.report":"comments.analysis";
        else if("video.metric".equals(base.type()))type="video.metric";
        else type=reading.reportRequired()?"danmaku.report":"danmaku.overview";
        if(type.equals(base.type()))return new Specification(type,base.conditions(),base.provenance(),reading);
        java.util.Map<String,Object> conditions;
        if(type.equals("video.content")) {
            var values=new java.util.LinkedHashMap<String,Object>();values.put("video_id",id);
            if(reading.videoStartMs()!=null){values.put("start_ms",reading.videoStartMs());values.put("end_ms",reading.videoEndMs());} conditions=values;
        } else if(typed instanceof com.tianmu.agent.core.TaskConditions.Report)conditions=base.conditions();
        else {
            var from=typed instanceof com.tianmu.agent.core.TaskConditions.Metric metric?metric.date().atStartOfDay():com.tianmu.agent.core.DanmakuTypes.ALL_TIME_START;
            var to=typed instanceof com.tianmu.agent.core.TaskConditions.Metric metric?metric.date().plusDays(1).atStartOfDay():at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().withNano(0);
            conditions=Map.of("video_id",id,"start",from.toString(),"end",to.toString());
        }
        return new Specification(type,conditions,base.provenance(),reading);
    }
    public Specification resolve(String text,LocalDate date,java.time.Instant at,com.tianmu.agent.core.MemoryTypes.View memory) {
        var focus=memory.focus();
        if(focus!=null && !focus.request().equals(text) && AnalysisSourceSelection.mentions(text)
                && com.tianmu.agent.application.ConversationFocus.continuation(text)
                && (memory.environment()==null || java.util.Objects.equals(memory.environment().videoId(),focus.referenceVideoId()))) {
            var base=focus.task();
            if(base==null) {
                var anchor=memory.recent().stream().filter(m->m.runId().equals(focus.sourceRunId()) && "user".equals(m.role())).map(com.tianmu.agent.core.MemoryTypes.Message::at).findFirst().orElse(at);
                base=resolve(focus.request(),anchor.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),anchor,memory.environment());
            }
            if(base.reading()!=null) {
                var reading=AnalysisSourceSelection.select(text,base.reading());
                reading=refresh(text,playback(text,base,reading));
                return selected(base,reading,date,at);
            }
        }
        return resolve(text,date,at,memory.environment());
    }
    public Specification resolve(String text,LocalDate date,java.time.Instant at,com.tianmu.agent.core.RequestContext page) {
        String parsing=page!=null && VideoDiscovery.request(text,date).isPresent()?VideoDiscovery.analysisText(text):text;
        var spec = resolve(com.tianmu.agent.application.RequestMessages.forParsing(parsing,page),date,at);
        if (page == null || com.tianmu.agent.core.DanmakuTarget.matcher(text).find() || !spec.conditions().containsKey("video_id")) return spec;
        Map<String,String> provenance = new HashMap<>(spec.provenance());provenance.put("video_id","page_context");
        return new Specification(spec.type(),spec.conditions(),provenance,spec.analysis());
    }
    /** Only an exposed report capability can fill a grammar gap. Values come from the original
     * request/page and existing parsers, never from model-extracted IDs, dates or related goals. */
    public Optional<Specification> reportCandidate(com.tianmu.agent.core.PreparationTypes.Input input,
            com.tianmu.agent.core.PreparationTypes.IntentRecognition intent) {
        String text=input.query();String capability=intent.primary();
        if(!Set.of("audience-report","video-content").contains(capability==null?"":capability)
                || input.intentCandidates().stream().noneMatch(c->c.id().equals(capability))
                || com.tianmu.agent.core.ConversationTopics.general(text)
                || text.matches("(?s).*(?:如何|怎样|怎么|什么是|教程|介绍).*" )
                || !text.matches("(?s).*(?:生成|制作|做|出|写|给我|来).{0,16}(?:报告|复盘).*"))return Optional.empty();
        if(text.matches("(?s).*(?:发送|发布|投递|删除|覆盖|授权|付费|购买|私信|邮箱).*"))return Optional.empty();
        if(text.matches("(?s).*(?:只|仅|排除|不要|不看|不读).*" ) && !AnalysisSourceSelection.mentions(text))
            throw new com.tianmu.agent.core.AgentException("AMBIGUOUS_ANALYSIS_SOURCES","请说明报告允许读取哪些来源；未确认的限制不会用默认模板覆盖。");
        String targeted=input.targetedQuery();var ids=com.tianmu.agent.core.DanmakuTarget.matcher(targeted).results().toList();
        if(ids.size()!=1)throw new com.tianmu.agent.core.AgentException("AMBIGUOUS_ANALYSIS_SOURCES",ids.isEmpty()?"要为哪个视频生成报告？请先选择视频或提供视频ID。":"这次要为哪个视频生成报告？请先选定一个视频。");
        long video=Long.parseLong(ids.get(0).group(1));
        if(!input.scope().readableVideoIds().contains(video))throw new com.tianmu.agent.core.AgentException("FORBIDDEN","Video is outside the authenticated scope");
        try {
            Specification base;
            boolean generic=!AnalysisSourceSelection.mentions(text) && !AudienceReportTask.requested(text);
            boolean audience=generic?input.scope().videoIds().contains(video) && input.intentCandidates().stream().anyMatch(c->c.id().equals("audience-report")):capability.equals("audience-report");
            if(audience){
                var query=DanmakuReportTask.parse(targeted,input.businessDate(),input.requestedAt().atZone(ZoneId.of("Asia/Shanghai")));
                query.checkTime(input.requestedAt());
                base=new Specification("audience.report",DanmakuReportTask.specification(query).conditions(),Map.of(),com.tianmu.agent.core.AnalysisContract.legacy("audience.report"));
            }else{
                var videoSpec=VideoContentTask.parse(targeted);
                base=new Specification("video.content",videoSpec.conditions(),Map.of(),new com.tianmu.agent.core.AnalysisContract(Set.of(com.tianmu.agent.core.AnalysisContract.Source.VIDEO),true));
            }
            var reading=AnalysisSourceSelection.select(text,base.reading());
            var result=selected(base,refresh(text,playback(text,base,reading)),input.businessDate(),input.requestedAt());
            if(!result.reading().textSources().isEmpty() && !input.scope().videoIds().contains(video))
                throw new com.tianmu.agent.core.AgentException("FORBIDDEN","Feedback reports require author access to this video");
            var provenance=new HashMap<String,String>();result.conditions().keySet().forEach(k->provenance.put(k,"server_parsed"));
            provenance.put("video_id",com.tianmu.agent.core.DanmakuTarget.matcher(text).find()?"user_input":"page_context");
            provenance.put("report_template","skill_default");
            if(!AnalysisSourceSelection.mentions(text))provenance.put("sources","skill_default");
            if(result.typed() instanceof com.tianmu.agent.core.TaskConditions.Report report && report.query().allTime()){
                provenance.put("start","skill_default");provenance.put("end","request_time");
            }
            return Optional.of(new Specification(result.type(),result.conditions(),provenance,result.analysis()));
        }catch(com.tianmu.agent.core.AgentException e){if(!e.code().equals("DATE_OUT_OF_RANGE"))throw e;throw new com.tianmu.agent.core.AgentException("AMBIGUOUS_ANALYSIS_SOURCES","请提供有效的已结束时间范围；明确指定的范围不会被默认值覆盖。");}
        catch(IllegalArgumentException e){throw new com.tianmu.agent.core.AgentException("AMBIGUOUS_ANALYSIS_SOURCES",e.getMessage());}
    }
}
