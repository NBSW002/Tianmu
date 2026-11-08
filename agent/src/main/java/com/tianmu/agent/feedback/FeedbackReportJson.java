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
                            List<FeedbackCompression.Representative> representatives) {
        public TopicView {representatives=List.copyOf(representatives);}
    }
    public FeedbackReportJson {topics=List.copyOf(topics);otherTopics=List.copyOf(otherTopics);unclassifiedExamples=unclassifiedExamples==null?List.of():List.copyOf(unclassifiedExamples);daily=daily==null?Map.of():Collections.unmodifiableMap(new TreeMap<>(daily));var normalized=new LinkedHashMap<String,Object>(method);normalized.replaceAll((k,v)->v instanceof Integer || v instanceof Short || v instanceof Byte?((Number)v).longValue():v);method=Map.copyOf(normalized);scope=Map.copyOf(scope);}
    public static FeedbackReportJson build(Asset asset,FeedbackCompression.Material material,List<Topic> roots,
            java.util.function.Function<Topic,List<FeedbackModels.Group>> groupsForTopic){
        var cards=new ArrayList<TopicView>();var seen=new HashSet<String>();var clusterTopic=new HashMap<String,String>();
        for(var topic:roots.stream().sorted(Comparator.comparingLong(Topic::messages).reversed().thenComparing(Topic::id)).toList()){
            var groups=groupsForTopic.apply(topic);long comments=0,danmaku=0;var clusterIds=new LinkedHashSet<String>();
            for(var group:groups){
                for(var id:group.originalIds()){if(!seen.add(id))throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Report membership repeated");}
                if(group.source()==Source.COMMENT)comments+=group.messages();else danmaku+=group.messages();
                String key=group.candidateCluster();if(key==null)throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Representative cluster reference missing");
                var prior=clusterTopic.putIfAbsent(key,topic.id());if(prior!=null && !prior.equals(topic.id()))throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","A representative cluster was split across final topics");clusterIds.add(key);
            }
            if(comments+danmaku!=topic.messages())throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Report topic counts differ from membership");
            var representatives=material.clusters().stream().filter(c->clusterIds.contains(c.id())).flatMap(c->c.representatives().stream()).limit(3).toList();
            cards.add(new TopicView(topic.id(),topic.title(),topic.observation(),topic.recommendation(),comments,danmaku,representatives));
        }
        for(var id:material.unclassified())if(!seen.add(id))throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Unclassified membership repeated");
        if(seen.size()!=material.eligibleCounts().total())throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Bucket and unclassified counts do not reconcile");
        var method=new LinkedHashMap<String,Object>(material.summary());method.put("raw_comments",material.rawCounts().comments());method.put("raw_danmaku",material.rawCounts().danmaku());method.put("raw_audience",material.rawCounts().total()-material.rawCounts().authorComments()-material.rawCounts().authorDanmaku());method.put("raw_unique_audience",material.rawCounts().uniqueAudience());method.put("statistics_available",material.stats()!=null);method.put("interpretation","主题依据聚类代表归纳，桶内数量为近似向量分配，未逐条阅读全部原文或经模型确认；未归类可能因相似度、支持度或候选限制。");method.put("likes_available",material.clusters().stream().flatMap(c->c.representatives().stream()).filter(r->r.likes()!=null).count());
        String summary=cards.isEmpty()?(material.eligibleCounts().total()==0?"本次范围内没有符合筛选条件的观众反馈。":"本次反馈较少或尚未形成可靠主题，单条反馈保留在未归类中。") :cards.stream().limit(5).map(TopicView::title).collect(java.util.stream.Collectors.joining("、","主要反馈涉及：","。"));
        var scope=Map.<String,Object>of("video_id",Long.toString(asset.request().query().videoId()),"start",asset.request().query().start().toString(),"end",asset.request().query().end().toString(),"timezone","Asia/Shanghai","sources",asset.request().sources().stream().map(Enum::name).sorted().toList(),"grounding",asset.request().grounding().name());
        var stats=material.stats();var playback=stats!=null && asset.request().sources().contains(Source.DANMAKU)?FeedbackPlaybackTrend.buildCounts(asset,stats.playback(),stats.playbackMissing(),material.rawCounts().danmaku()):null;
        FeedbackCompressionProperties reportPolicy=material.policy();var v=asset.video();var videoDetails=v==null?null:new VideoDetails(v.startMs(),v.endMs(),v.sampledAtMs(),v.moments(),!v.transcript().isBlank(),reportPolicy.momentMatchMaxGapMs());
        return new FeedbackReportJson("feedback-report-json-v1","视频"+asset.request().query().videoId()+"观众反馈报告",summary,cards.stream().limit(5).toList(),cards.stream().skip(5).toList(),method,v==null?null:v.summary(),scope,asset.snapshot().dataAsOf(),v==null?null:v.coverage(),material.unclassifiedExamples(),stats==null?Map.of():stats.daily(),playback,videoDetails);
    }
    public byte[] html(){
        var h=new StringBuilder("<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>").append(escape(title)).append("</title><style>").append(FeedbackReportRenderer.styles()).append(FeedbackReportCharts.STYLE).append("</style><main><header><h1>这条视频，观众怎么说</h1><p class='muted'>范围：").append(escape(scope.get("start").toString())).append(" 至 ").append(escape(scope.get("end").toString())).append("（北京时间，结束时刻不计入）</p><div class='metrics'><div><strong>").append(method.get("raw")).append("</strong><span>条原始反馈</span></div><div><strong>").append(method.get("eligible")).append("</strong><span>条有效反馈</span></div><div><strong>").append(method.get("clusters")).append("</strong><span>个主题桶</span></div></div><p>").append(escape(summary)).append("</p></header><section>");
        if(videoSummary!=null){h.append("<details><summary>视频语境与覆盖</summary><p>").append(escape(videoSummary)).append("</p><p>").append(escape(Objects.toString(videoCoverage,""))).append("</p>");if(videoDetails!=null){h.append("<p>采样 ").append(videoDetails.sampledAtMs().size()).append(" 个位置；").append(videoDetails.transcriptAvailable()?"有音频转录":"未提供音频转录").append("。</p>");for(var moment:videoDetails.moments())h.append("<p>").append(moment.atMs()/1000.0).append(" 秒：").append(escape(moment.observation())).append("</p>");}h.append("</details>");}
        if(!topics.isEmpty())h.append("<h2>观众关注的 ").append(topics.size()).append(" 个话题</h2>");for(var topic:topics)card(h,topic);
        if(!unclassifiedExamples.isEmpty()){h.append("<details open><summary>尚未成组的反馈</summary>");for(var item:unclassifiedExamples)h.append("<blockquote>").append(escape(item.text())).append("</blockquote>");h.append("</details>");}
        if(!otherTopics.isEmpty()){h.append("<details><summary>其他话题</summary>");otherTopics.forEach(t->card(h,t));h.append("</details>");}
        h.append("</section>");if(playback!=null){h.append("<section>");FeedbackReportCharts.playback(h,playback);h.append("</section>");}h.append("<section class='method'><h2>数据说明</h2><p>内容根据代表反馈归纳，相关数量为近似统计。");if(videoSummary!=null)h.append(" 视频分析依据采样画面。");h.append("</p>");
        if(!daily.isEmpty()){h.append("<details class='daily-data'><summary>按发送日期查看反馈</summary><p>按反馈发送日期统计，与视频播放位置不同。</p><table><thead><tr><th>日期</th><th>反馈记录</th></tr></thead><tbody>");daily.forEach((day,n)->h.append("<tr><th>").append(escape(day)).append("</th><td>").append(n).append("</td></tr>"));h.append("</tbody></table></details>");}
        if(Boolean.FALSE.equals(method.get("statistics_available")))h.append("<p>本次材料未保存完整时间统计，无法展示时间图表。</p>");
        h.append("</section></main></html>");return h.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void card(StringBuilder h,TopicView t){h.append("<article class='topic'><div class='topic-head'><h3>").append(escape(t.title())).append("</h3><span class='badge'>").append(t.commentCount()+t.danmakuCount()).append(" 条桶内反馈</span></div><p class='action-preview'><span>建议</span>").append(escape(t.recommendation())).append("</p><details class='topic-detail'><summary>观众原话与完整建议</summary><p class='muted'>评论 ").append(t.commentCount()).append(" 条 · 弹幕 ").append(t.danmakuCount()).append(" 条（近似分桶统计）</p><p>").append(escape(t.observation())).append("</p><p class='label'>原话与来源</p>");for(var r:t.representatives()){h.append("<blockquote>").append(escape(r.text())).append("<footer>").append(r.source()==Source.COMMENT?"评论":"弹幕").append(" · 点赞 ").append(r.likes()==null?"未知":r.likes()).append(" · 相同语境表达 ").append(r.exactMessages()).append(" 条");String video=Objects.toString(scope.get("video_id"),"");if(r.playbackMs()!=null && video.matches("[1-9][0-9]{0,18}"))h.append(" · <a target='_blank' rel='noopener noreferrer' href='/video/").append(video).append("?t=").append(r.playbackMs()/1000.0).append("'>查看视频对应位置 ↗</a>");h.append(r.excerpt()?" · 节选":"").append("</footer>");if(!r.context().isBlank())h.append("<details class='reply-context'><summary>查看回复上文</summary><p>").append(escape(r.context())).append("</p></details>");h.append("</blockquote>");}appendMoments(h,t);h.append("<div class='action'><p class='label'>可以怎么做</p><p>").append(escape(t.recommendation())).append("</p></div></details></article>");}
    private void appendMoments(StringBuilder h,TopicView topic){
        if(videoDetails==null || videoDetails.moments().isEmpty())return;
        var selected=new LinkedHashMap<Long,VideoContent.Moment>();
        for(var rep:topic.representatives()){Long at=rep.playbackMs();if(at==null || at<videoDetails.startMs() || at>=videoDetails.endMs())continue;
            videoDetails.moments().stream().filter(m->Math.abs(m.atMs()-at)<=videoDetails.momentMatchMaxGapMs()).min(Comparator.comparingLong(m->Math.abs(m.atMs()-at))).ifPresent(m->selected.putIfAbsent(m.atMs(),m));if(selected.size()>=2)break;}
        if(selected.isEmpty())return;h.append("<div class='context'><p class='label'>原话播放位置附近的采样画面</p>");
        for(var moment:selected.values())h.append("<p>").append(moment.atMs()/1000.0).append(" 秒：").append(escape(moment.observation())).append("</p>");
        h.append("<small>仅供画面语境参考，不证明声音或连续动作。</small></div>");
    }
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
