package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.core.VideoContent;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Canonical report asset. Narratives come from accepted model topics; all counts and quotes are host-bound. */
public record FeedbackReportJson(String schemaVersion,String title,String summary,List<TopicView> topics,
        List<TopicView> otherTopics,Map<String,Object> method,String videoSummary,Map<String,Object> scope,java.time.Instant dataAsOf,String videoCoverage,List<FeedbackCompression.Representative> unclassifiedExamples,Map<String,Long> daily,FeedbackPlaybackTrend playback,VideoDetails videoDetails) {
    public record VideoDetails(long startMs,long endMs,List<Long> sampledAtMs,List<VideoContent.Moment> moments,boolean transcriptAvailable,int momentMatchMaxGapMs){public VideoDetails{sampledAtMs=List.copyOf(sampledAtMs);moments=List.copyOf(moments);}}
    public record TopicView(String id,String title,String observation,String recommendation,long commentCount,long danmakuCount,
                            List<FeedbackCompression.Representative> representatives,List<Long> selectedFrameMs) {
        public TopicView(String id,String title,String observation,String recommendation,long commentCount,long danmakuCount,List<FeedbackCompression.Representative> representatives){this(id,title,observation,recommendation,commentCount,danmakuCount,representatives,null);}
        public TopicView {representatives=List.copyOf(representatives);selectedFrameMs=selectedFrameMs==null?null:List.copyOf(selectedFrameMs);}
    }
    public FeedbackReportJson {topics=List.copyOf(topics);otherTopics=List.copyOf(otherTopics);unclassifiedExamples=unclassifiedExamples==null?List.of():List.copyOf(unclassifiedExamples);daily=daily==null?Map.of():Collections.unmodifiableMap(new TreeMap<>(daily));var normalized=new LinkedHashMap<String,Object>(method);normalized.replaceAll((k,v)->v instanceof Integer || v instanceof Short || v instanceof Byte?((Number)v).longValue():v);method=Map.copyOf(normalized);scope=Map.copyOf(scope);}
    public static FeedbackReportJson build(Asset asset,FeedbackCompression.Material material,List<Topic> roots,
            java.util.function.Function<Topic,List<FeedbackModels.Group>> groupsForTopic){
        boolean reranked=FeedbackCompression.RERANK_VERSION.equals(material.version());
        var cards=new ArrayList<TopicView>();var seen=new HashSet<String>();var clusterTopic=new HashMap<String,String>();long excludedRecords=0;
        for(var topic:roots.stream().sorted(Comparator.comparingLong(Topic::messages).reversed().thenComparing(Topic::id)).toList()){
            var groups=groupsForTopic.apply(topic);long comments=0,danmaku=0;var clusterIds=new LinkedHashSet<String>();
            for(var group:groups){
                for(var id:group.originalIds()){if(!seen.add(id) && !reranked)throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Report membership repeated");}
                if(group.source()==Source.COMMENT)comments+=group.messages();else danmaku+=group.messages();
                String key=group.candidateCluster();if(key==null)throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Representative cluster reference missing");
                var prior=clusterTopic.putIfAbsent(key,topic.id());if(prior!=null && !prior.equals(topic.id()) && !reranked)throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","A representative cluster was split across final topics");clusterIds.add(key);
            }
            if(comments+danmaku!=topic.messages())throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Report topic counts differ from membership");
            if(topic.selection()!=null && "EXCLUDED".equals(topic.selection().coherence())){excludedRecords+=topic.messages();continue;}
            var evidenceIds=new HashSet<>(topic.members());
            var candidates=material.clusters().stream().filter(c->clusterIds.contains(c.id())).flatMap(c->c.representatives().stream())
                    .filter(rep->!reranked || evidenceIds.contains(rep.id())).toList();
            var byId=new HashMap<String,FeedbackCompression.Representative>();candidates.forEach(r->byId.put(r.id(),r));
            var representatives=topic.selection()==null?candidates.stream().limit(3).toList():topic.selection().representativeIds().stream().map(id->{var rep=byId.get(id);if(rep==null)throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Report selection outside sealed candidates");return rep;}).toList();
            cards.add(new TopicView(topic.id(),topic.title(),topic.observation(),topic.recommendation(),comments,danmaku,representatives,topic.selection()==null?null:topic.selection().frameMs()));
        }
        var reconciled=reranked?new HashSet<String>():seen;
        if(reranked){
            for(var cluster:material.clusters())for(var id:cluster.members())
                if(!reconciled.add(id))throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Bucket membership repeated");
            if(!reconciled.containsAll(seen))throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Confirmed feedback outside buckets");
        }
        for(var id:material.unclassified())if(!reconciled.add(id))throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Unclassified membership repeated");
        if(reconciled.size()!=material.eligibleCounts().total())throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Bucket and unclassified counts do not reconcile");
        var method=new LinkedHashMap<String,Object>(material.summary());method.put("raw_comments",material.rawCounts().comments());method.put("raw_danmaku",material.rawCounts().danmaku());method.put("raw_audience",material.rawCounts().total()-material.rawCounts().authorComments()-material.rawCounts().authorDanmaku());method.put("raw_unique_audience",material.rawCounts().uniqueAudience());method.put("statistics_available",material.stats()!=null);method.put("interpretation","主题依据聚类代表归纳，桶内数量为近似向量分配，未逐条阅读全部原文或经模型确认；未归类可能因相似度、支持度或候选限制。");method.put("likes_available",material.clusters().stream().flatMap(c->c.representatives().stream()).filter(r->r.likes()!=null).count());
        method.put("topics",cards.size());method.put("quality_excluded_records",excludedRecords);method.put("unclassified",material.unclassified().size()+excludedRecords);
        if(reranked){
            method.put("confirmed_records",seen.size());
            method.put("unclassified",material.eligibleCounts().total()-seen.size());
            method.put("rerank_degraded",material.inputs().stream().anyMatch(FeedbackModels.Input::rerankDegraded));
            method.put("interpretation","主题数量仅统计已确认的证据成员。未确认反馈仍保留在快照中，不能据此推断全量主题占比。");
        }
        String summary=cards.isEmpty()?(material.eligibleCounts().total()==0?"本次范围内没有符合筛选条件的观众反馈。":"本次反馈尚未形成可靠主题，相关反馈保留在未归类中。") :cards.stream().limit(5).map(TopicView::title).collect(java.util.stream.Collectors.joining("、","主要反馈涉及：","。"));
        var scope=Map.<String,Object>of("video_id",Long.toString(asset.request().query().videoId()),"start",asset.request().query().start().toString(),"end",asset.request().query().end().toString(),"timezone","Asia/Shanghai","sources",asset.request().sources().stream().map(Enum::name).sorted().toList(),"grounding",asset.request().grounding().name());
        var stats=material.stats();var playback=stats!=null && asset.request().sources().contains(Source.DANMAKU)?FeedbackPlaybackTrend.buildCounts(asset,stats.playback(),stats.playbackMissing(),material.rawCounts().danmaku()):null;
        FeedbackCompressionProperties reportPolicy=material.policy();var v=asset.video();var videoDetails=v==null?null:new VideoDetails(v.startMs(),v.endMs(),v.sampledAtMs(),v.moments(),!v.transcript().isBlank(),reportPolicy.momentMatchMaxGapMs());
        return new FeedbackReportJson("feedback-report-json-v1","观众反馈报告",summary,cards.stream().limit(5).toList(),cards.stream().skip(5).toList(),method,v==null?null:v.summary(),scope,asset.snapshot().dataAsOf(),v==null?null:v.coverage(),material.unclassifiedExamples(),stats==null?Map.of():stats.daily(),playback,videoDetails);
    }
    private boolean reranked(){return FeedbackCompression.RERANK_VERSION.equals(method.get("method"));}
    public byte[] html(){
        String videoId=Objects.toString(scope.get("video_id"),"");
        String videoTitle=Objects.toString(scope.get("video_title"),"").strip();
        if(videoTitle.isEmpty())videoTitle="这条视频，观众怎么说";
        var h=new StringBuilder("<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>")
                .append(escape(videoTitle)).append(" · 观众反馈报告</title><style>").append(FeedbackReportRenderer.styles())
                .append(FeedbackReportCharts.STYLE).append(READER_STYLE).append("</style><main><header>");
        h.append("<p class='eyebrow'>观众反馈报告</p><h1>").append(escape(videoTitle)).append("</h1>");
        if(videoId.matches("[1-9][0-9]{0,18}"))h.append("<a href='/video/").append(videoId).append("'>查看视频 ↗</a>");
        h.append("<p class='muted'>").append(escape(readableRange())).append("</p>");
        if(videoSummary!=null)h.append("<p class='video-intro'>").append(escape(videoSummary)).append("</p>");
        h.append("<p class='report-lead'>").append(escape(summary)).append("</p>");
        h.append("<p class='coverage-line'>采集 ").append(method.get("raw")).append(" 条 · 纳入分析 ")
                .append(method.get("eligible")).append(" 条 · 归纳 ").append(method.getOrDefault("topics",method.get("clusters"))).append(" 个话题</p></header>");
        if(playback!=null){h.append("<section>");FeedbackReportCharts.playback(h,playback);h.append("</section>");}
        h.append("<section>");
        if(videoSummary!=null){h.append("<details><summary>查看视频片段说明</summary>");if(videoDetails!=null){h.append("<p>画面说明来自视频中的 ").append(videoDetails.sampledAtMs().size()).append(" 个位置。");if(!videoDetails.transcriptAvailable())h.append("声音相关评价来自观众反馈，尚未独立核实。");h.append("</p>");for(var moment:videoDetails.moments())h.append("<p>").append(moment.atMs()/1000.0).append(" 秒：").append(escape(moment.observation())).append("</p>");}h.append("</details>");}
        if(!topics.isEmpty())h.append("<h2>观众关注的 ").append(topics.size()).append(" 个话题</h2>");for(var topic:topics)card(h,topic);
        if(!unclassifiedExamples.isEmpty()){h.append("<details><summary>其他反馈摘录</summary>");for(var item:unclassifiedExamples)h.append("<blockquote>").append(escape(item.text())).append("</blockquote>");h.append("</details>");}
        if(!otherTopics.isEmpty()){h.append("<details><summary>其他话题</summary>");otherTopics.forEach(t->card(h,t));h.append("</details>");}
        h.append("</section><footer class='report-footer'>根据本次已采集的反馈归纳，所列原话为部分摘录。</footer></main></html>");return h.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void card(StringBuilder h,TopicView topic){
        h.append("<article class='topic'><div class='topic-head'><h3>").append(escape(topic.title()))
                .append("</h3></div><p class='observation'>").append(escape(topic.observation())).append("</p>");
        if(!topic.representatives().isEmpty())quote(h,topic.representatives().get(0));
        h.append("<p class='evidence-note'>本节引用：");
        if(topic.commentCount()>0)h.append("评论 ").append(topic.commentCount()).append(" 条");
        if(topic.commentCount()>0 && topic.danmakuCount()>0)h.append(" · ");
        if(topic.danmakuCount()>0)h.append("弹幕 ").append(topic.danmakuCount()).append(" 条");
        h.append("</p>");
        h.append("<details class='topic-detail'><summary>更多原话与片段依据</summary>");
        for(int i=1;i<topic.representatives().size();i++)quote(h,topic.representatives().get(i));
        appendMoments(h,topic);
        h.append("</details>");
        if(topic.recommendation()!=null && !topic.recommendation().isBlank())
            h.append("<div class='action'><p class='label'>创作建议</p><p>")
                    .append(escape(topic.recommendation())).append("</p></div>");
        h.append("</article>");
    }
    private void quote(StringBuilder h,FeedbackCompression.Representative quote){
        h.append("<blockquote>").append(escape(quote.text())).append("<footer>")
                .append(quote.source()==Source.COMMENT?"评论":"弹幕");
        if(quote.likes()!=null && quote.likes()>0)h.append(" · ").append(quote.likes()).append(" 赞");
        if(quote.exactMessages()>1)h.append(" · 同一讨论背景下，有 ").append(quote.exactMessages()).append(" 条相同表达");
        String videoId=Objects.toString(scope.get("video_id"),"");
        if(quote.playbackMs()!=null && videoId.matches("[1-9][0-9]{0,18}"))
            h.append(" · <a href='/video/").append(videoId).append("?t=").append(quote.playbackMs()/1000.0)
                    .append("' target='_blank' rel='noopener noreferrer'>查看对应片段 ↗</a>");
        if(quote.excerpt())h.append(" · 原话节选");
        h.append("</footer>");
        if(!quote.context().isBlank())h.append("<details class='reply-context'><summary>查看回复上文</summary><p>")
                .append(escape(FeedbackReportDocument.replyContext(quote.context()))).append("</p></details>");
        h.append("</blockquote>");
    }
    private String readableRange(){
        String start=Objects.toString(scope.get("start"),"");
        String end=Objects.toString(scope.get("end"),"");
        try{
            var format=java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
            String until=java.time.LocalDateTime.parse(end).format(format);
            return start.startsWith("1970-01-01")?"本次已采集反馈 · 截至 "+until+"（北京时间）":
                    java.time.LocalDateTime.parse(start).format(format)+" 至 "+until+"（北京时间）";
        }catch(java.time.format.DateTimeParseException invalid){
            return "按本次保存的反馈范围统计";
        }
    }
    private static final String READER_STYLE="""
            @font-face{font-family:ReportSans;src:local('Microsoft YaHei'),local('微软雅黑');font-weight:400;font-style:normal}
            @font-face{font-family:ReportSans;src:local('Microsoft YaHei Bold'),local('微软雅黑 Bold');font-weight:600 700;font-style:normal}
            body{font-family:ReportSans,'PingFang SC','Noto Sans CJK SC',Arial,sans-serif;font-size:15px;line-height:1.8;-webkit-font-smoothing:antialiased}
            h1,h2,h3{font-weight:500}h2{font-size:22px}
            main{max-width:900px}h1{font-size:28px;line-height:1.4;letter-spacing:0}
            header,section,.method{border-radius:12px}
            header{border:0;background:#eae7de}header .eyebrow{margin:0 0 8px}
            section{border-color:#dedbd1}
            section:has(>.trend-chart){background:transparent;border:0;border-radius:0;padding:8px 0 24px}
            .trend-chart{border:0;border-radius:0;background:transparent;padding:0 24px}
            .trend-chart h3{font-size:22px;font-weight:500}.trend-chart .eyebrow{display:none}
            .trend-chart .chart-note{font-size:13px;color:#77756e}
            .peak-callout{font-size:14px;gap:10px;margin:20px 0 18px}.peak-callout strong{font-size:18px;font-weight:600}
            .peak-dot{display:none}.trend-plot{--plot-height:156px}.x-axis,.y-axis{font-family:ReportSans,Arial,sans-serif;font-variant-numeric:tabular-nums}
            .report-lead{font-size:18px;line-height:1.7;font-weight:500;max-width:38em}
            .video-intro,.observation{max-width:40em}.coverage-line{font-size:14px;color:#5e5d59}
            .topic{padding:32px 0}.topic h3{font-size:22px}.topic .observation{margin:12px 0 20px}
            .topic-detail>summary,.chart-note,.chart-data,.reply-context{font-size:14px}
            .evidence-note,.report-footer{font-size:12px;color:#77756e}.report-footer{padding:0 6px 24px}
            .action{background:transparent;border-top:1px solid #dedbd1;border-radius:0;padding:16px 0 0;margin-top:16px}
            blockquote{background:#f0eee6;border-left:2px solid #8f8c80;border-radius:0 4px 4px 0}
            .context{background:#eeeee6;border-radius:4px}
            .topic-head{align-items:flex-start}.topic-detail{border-radius:0}
            .x-axis,.y-axis{font-size:12px}
            @media(max-width:600px){h1{font-size:25px}.report-lead{font-size:18px}.topic h3{font-size:21px}
            header,section,.method{border-radius:8px}.trend-chart{padding:0 8px}}
            """;
    private void appendMoments(StringBuilder h,TopicView topic){
        if(videoDetails==null || videoDetails.moments().isEmpty())return;
        var selected=new LinkedHashMap<Long,VideoContent.Moment>();
        if(topic.selectedFrameMs()!=null){for(long at:topic.selectedFrameMs())videoDetails.moments().stream().filter(m->m.atMs()==at).findFirst().ifPresent(m->selected.put(at,m));}
        else
        for(var rep:topic.representatives()){Long at=rep.playbackMs();if(at==null || at<videoDetails.startMs() || at>=videoDetails.endMs())continue;
            videoDetails.moments().stream().filter(m->Math.abs(m.atMs()-at)<=videoDetails.momentMatchMaxGapMs()).min(Comparator.comparingLong(m->Math.abs(m.atMs()-at))).ifPresent(m->selected.putIfAbsent(m.atMs(),m));if(selected.size()>=2)break;}
        if(selected.isEmpty())return;h.append("<div class='context'><p class='label'>").append(topic.selectedFrameMs()==null?"这条弹幕附近的画面":"相关视频画面").append("</p>");
        for(var moment:selected.values())h.append("<p>").append(moment.atMs()/1000.0).append(" 秒：").append(escape(moment.observation())).append("</p>");
        h.append("<small>仅供画面语境参考，不证明声音或连续动作。</small></div>");
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
