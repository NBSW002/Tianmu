package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Canonical report asset. Narratives come from accepted model topics; all counts and quotes are host-bound. */
public record FeedbackReportJson(String schemaVersion,String title,String summary,List<TopicView> topics,
        List<TopicView> otherTopics,Map<String,Object> method,String videoSummary,Map<String,Object> scope,java.time.Instant dataAsOf,String videoCoverage,List<FeedbackCompression.Representative> unclassifiedExamples) {
    public record TopicView(String id,String title,String observation,String recommendation,long commentCount,long danmakuCount,
                            List<FeedbackCompression.Representative> representatives) {
        public TopicView {representatives=List.copyOf(representatives);}
    }
    public FeedbackReportJson {topics=List.copyOf(topics);otherTopics=List.copyOf(otherTopics);unclassifiedExamples=unclassifiedExamples==null?List.of():List.copyOf(unclassifiedExamples);var normalized=new LinkedHashMap<String,Object>(method);normalized.replaceAll((k,v)->v instanceof Integer || v instanceof Short || v instanceof Byte?((Number)v).longValue():v);method=Map.copyOf(normalized);scope=Map.copyOf(scope);}
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
            var representatives=material.clusters().stream().filter(c->clusterIds.contains(c.id())).flatMap(c->c.representatives().stream()).toList();
            cards.add(new TopicView(topic.id(),topic.title(),topic.observation(),topic.recommendation(),comments,danmaku,representatives));
        }
        for(var id:material.unclassified())if(!seen.add(id))throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Unclassified membership repeated");
        if(seen.size()!=material.eligibleCounts().total())throw new com.tianmu.agent.core.AgentException("INVALID_ANALYSIS","Bucket and unclassified counts do not reconcile");
        var method=new LinkedHashMap<String,Object>(material.summary());method.put("interpretation","主题依据聚类代表归纳，桶内数量为近似向量分配，未逐条阅读全部原文或经模型确认；未归类可能因相似度、支持度或候选限制。");method.put("likes_available",material.clusters().stream().flatMap(c->c.representatives().stream()).filter(r->r.likes()!=null).count());
        String summary=cards.isEmpty()?(material.eligibleCounts().total()==0?"本次范围内没有符合筛选条件的观众反馈。":"本次反馈较少或尚未形成可靠主题，单条反馈保留在未归类中。") :cards.stream().limit(5).map(TopicView::title).collect(java.util.stream.Collectors.joining("、","主要反馈涉及：","。"));
        var scope=Map.<String,Object>of("video_id",Long.toString(asset.request().query().videoId()),"start",asset.request().query().start().toString(),"end",asset.request().query().end().toString(),"timezone","Asia/Shanghai","sources",asset.request().sources().stream().map(Enum::name).sorted().toList(),"grounding",asset.request().grounding().name());
        return new FeedbackReportJson("feedback-report-json-v1","视频"+asset.request().query().videoId()+"观众反馈报告",summary,cards.stream().limit(5).toList(),cards.stream().skip(5).toList(),method,asset.video()==null?null:asset.video().summary(),scope,asset.snapshot().dataAsOf(),asset.video()==null?null:asset.video().coverage(),material.unclassifiedExamples());
    }
    public byte[] html(){
        var h=new StringBuilder("<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>").append(escape(title)).append("</title><style>body{margin:0;background:#f5f4ed;color:#242321;font:16px/1.8 'Microsoft YaHei',sans-serif}main{max-width:1000px;margin:auto;padding:36px 24px}article{border:1px solid #e8e6dc;background:#faf9f5;border-radius:16px;padding:24px;margin:24px 0}h1{font-size:28px}h2{font-size:22px}small,.muted{color:#727168}blockquote{margin:16px 0;border-left:3px solid #c96442;padding-left:16px}summary{cursor:pointer;color:#a55237}p{white-space:pre-wrap;overflow-wrap:anywhere}.method{font-size:13px;color:#727168}</style><main><h1>").append(escape(title)).append("</h1><p>").append(escape(summary)).append("</p>");
        h.append("<p class='muted'>范围：").append(escape(scope.get("start").toString())).append(" 至 ").append(escape(scope.get("end").toString())).append("（北京时间，结束时刻不计入）</p>");
        if(videoSummary!=null)h.append("<details><summary>视频语境与覆盖</summary><p>").append(escape(videoSummary)).append("</p><p>").append(escape(Objects.toString(videoCoverage,""))).append("</p></details>");
        for(var topic:topics)card(h,topic);
        if(!unclassifiedExamples.isEmpty()){h.append("<details open><summary>尚未成组的反馈</summary>");for(var item:unclassifiedExamples)h.append("<blockquote>").append(escape(item.text())).append("</blockquote>");h.append("</details>");}
        if(!otherTopics.isEmpty()){h.append("<details><summary>其他话题</summary>");otherTopics.forEach(t->card(h,t));h.append("</details>");}
        h.append("<section class='method'><h2>处理口径</h2><p>原始 ").append(method.get("raw")).append(" 条，排除 ").append(method.get("excluded")).append(" 条，纳入 ").append(method.get("eligible")).append(" 条；联合聚类 ").append(method.get("clusters")).append(" 簇，未归类 ").append(method.getOrDefault("unclassified",0)).append(" 条，模型读取 ").append(method.get("representatives")).append(" 条代表表达及有界语境。</p><p>").append(escape(Objects.toString(method.get("interpretation"),""))).append("</p><p>点赞缺失不视为零，缺失来源按频次及代表性排序；视频采样不等于完整逐帧或音频理解。</p></section></main></html>");return h.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void card(StringBuilder h,TopicView t){h.append("<article><h2>").append(escape(t.title())).append("</h2><small>评论 ").append(t.commentCount()).append(" 条 · 弹幕 ").append(t.danmakuCount()).append(" 条（簇成员统计）</small><p>").append(escape(t.observation())).append("</p><p>").append(escape(t.recommendation())).append("</p><details><summary>代表反馈与证据</summary>");for(var r:t.representatives()){h.append("<blockquote>").append(escape(r.text())).append("<br><small>").append(r.source()==Source.COMMENT?"评论":"弹幕").append(" · 点赞 ").append(r.likes()==null?"未知":r.likes()).append(" · 相同语境表达 ").append(r.exactMessages()).append(" 条 · ").append(escape(r.id())).append(r.excerpt()?" · 节选":"").append("</small></blockquote>");}h.append("</details></article>");}
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
