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
            var evidenceIds=groups.stream().map(FeedbackModels.Group::id).collect(java.util.stream.Collectors.toSet());
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
    public FeedbackReportJson withVideoTitle(String value){return withVideoMetadata(value,Objects.toString(scope.get("video_cover"),""));}
    public FeedbackReportJson withVideoMetadata(String value,String cover){
        var metadata=new LinkedHashMap<String,Object>(scope);
        if(value!=null && !value.isBlank())metadata.put("video_title",value);
        if(cover!=null && cover.length()<=350000 && cover.matches("data:image/(?:jpeg|png|gif|webp|avif);base64,[A-Za-z0-9+/=]+"))metadata.put("video_cover",cover);
        return new FeedbackReportJson(schemaVersion,title,summary,topics,otherTopics,method,videoSummary,metadata,dataAsOf,videoCoverage,unclassifiedExamples,daily,playback,videoDetails);
    }
    private boolean reranked(){return FeedbackCompression.RERANK_VERSION.equals(method.get("method"));}
    public byte[] html(){
        String videoId=Objects.toString(scope.get("video_id"),"");
        String videoTitle=Objects.toString(scope.get("video_title"),"").strip();
        String cover=Objects.toString(scope.get("video_cover"),"");
        if(cover.length()>350000 || !cover.matches("data:image/(?:jpeg|png|gif|webp|avif);base64,[A-Za-z0-9+/=]+"))cover="";
        if(videoTitle.isEmpty())videoTitle="这条视频，观众怎么说";
        var h=new StringBuilder("<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>")
                .append(escape(videoTitle)).append(" · 观众反馈报告</title><style>").append(FeedbackReportRenderer.styles())
                .append(FeedbackReportCharts.STYLE).append(READER_STYLE).append(EDITORIAL_STYLE).append("</style></head><body data-report-layout='editorial-v2'><main><div class='masthead'><span>EchoFrame</span><span>反馈报告</span></div><header>");
        h.append("<div class='report-heading'><p class='eyebrow'>观众反馈报告</p><h1>").append(escape(videoTitle)).append("</h1>");
        if(!cover.isBlank())h.append("<figure class='video-cover'><img src='").append(escape(cover)).append("' alt='").append(escape(videoTitle)).append("的视频封面' width='640' height='360'></figure>");
        h.append("<div class='report-meta'><span>").append(escape(readableRange())).append("</span>");
        if(videoId.matches("[1-9][0-9]{0,18}"))h.append("<a href='/video/").append(videoId).append("'>查看视频 ↗</a>");
        h.append("</div><p class='report-lead'>").append(escape(summary)).append("</p></div>");

        h.append("</header><div class='report-source'><span>共采集 ").append(number(method.get("raw"))).append(" 条评论与弹幕</span><span>")
                .append(number(method.getOrDefault("topics",method.get("clusters")))).append(" 个讨论话题</span></div>");
        h.append("<div class='report-layout'><div class='report-body'><section class='report-topics'>");
        if(videoDetails!=null){h.append("<details><summary>查看视频片段说明</summary>");if(videoDetails!=null){h.append("<p>画面说明来自视频中的 ").append(videoDetails.sampledAtMs().size()).append(" 个位置。");if(!videoDetails.transcriptAvailable())h.append("声音相关评价来自观众反馈，尚未独立核实。");h.append("</p>");for(var moment:videoDetails.moments())h.append("<p>").append(moment.atMs()/1000.0).append(" 秒：").append(escape(moment.observation())).append("</p>");}h.append("</details>");}
        if(!topics.isEmpty())h.append("<h2>观众关注的 ").append(topics.size()).append(" 个话题</h2>");for(int i=0;i<topics.size();i++)card(h,topics.get(i),i+1);
        if(!unclassifiedExamples.isEmpty()){h.append("<details><summary>其他反馈摘录</summary>");for(var item:unclassifiedExamples)h.append("<blockquote>").append(escape(item.text())).append("</blockquote>");h.append("</details>");}
        if(!otherTopics.isEmpty()){h.append("<details><summary>其他话题</summary>");for(int i=0;i<otherTopics.size();i++)card(h,otherTopics.get(i),topics.size()+i+1);h.append("</details>");}
        h.append("</section>");
        if(playback!=null){h.append("<section id='playback'>");FeedbackReportCharts.playback(h,playback);h.append("</section>");}
        var actions=topics.stream().filter(t->t.recommendation()!=null && !t.recommendation().isBlank()).toList();
        if(!actions.isEmpty()){
            h.append("<section id='creation'><h2>创作建议</h2>");
            for(var topic:actions)h.append("<div class='advice-row'><h3>").append(escape(topic.title())).append("</h3><p>").append(escape(topic.recommendation())).append("</p></div>");
            h.append("</section>");
        }
        h.append("<details class='report-method'><summary>关于本次分析</summary><p>共采集 ").append(number(method.get("raw"))).append(" 条反馈，去除纯表情、刷屏等低信息互动后，保留 ")
                .append(number(method.get("eligible"))).append(" 条；报告依据其中选取的代表原话归纳。筛选不改变弹幕时间图的原始计数。</p>");
        h.append("<p>根据本次已采集的反馈归纳，所列原话为部分摘录；不代表逐条阅读全部反馈，也不据此推断全体观众的观点比例。弹幕时间分布统计包含未进入主题分析的刷屏等互动，不代表观众留存率。</p></details><footer class='report-footer'>EchoFrame · 观众反馈报告</footer></div></div></main></body></html>");return h.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void card(StringBuilder h,TopicView topic,int ordinal){
        h.append("<article id='topic-").append(ordinal).append("' class='topic'><div class='topic-head'><span class='topic-number'>").append(String.format(java.util.Locale.ROOT,"%02d",ordinal)).append("</span><h3>").append(escape(topic.title()))
                .append("</h3></div><p class='observation'>").append(escape(topic.observation())).append("</p>");
        if(!topic.representatives().isEmpty())quote(h,topic.representatives().get(0));
        h.append("<p class='evidence-note'>本节引用：");
        if(topic.commentCount()>0)h.append("评论 ").append(topic.commentCount()).append(" 条");
        if(topic.commentCount()>0 && topic.danmakuCount()>0)h.append(" · ");
        if(topic.danmakuCount()>0)h.append("弹幕 ").append(topic.danmakuCount()).append(" 条");
        h.append("</p>");
        var more=new StringBuilder();
        for(int i=1;i<topic.representatives().size();i++)quote(more,topic.representatives().get(i));
        appendMoments(more,topic);
        if(!more.isEmpty())h.append("<details class='topic-detail'><summary>更多原话与片段依据</summary>").append(more).append("</details>");

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
    private static String number(Object value){
        return value instanceof Number n?java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n.longValue()):"—";
    }
    private static final String EDITORIAL_STYLE="""
            body{background:#faf9f5;color:#262922;letter-spacing:.015em;line-height:1.85}
            main{max-width:1080px;padding:28px 40px 48px;margin:auto}
            .masthead{display:flex;justify-content:space-between;align-items:baseline;padding-bottom:20px;border-bottom:1px solid #d9d8ce;font-size:12px;color:#72766b;letter-spacing:.04em}
            .masthead>span:first-child{font:25px Georgia,serif;color:#354332;letter-spacing:-.02em}
            header{display:grid;grid-template-columns:minmax(0,1fr) 280px;gap:40px;background:transparent;border:0;border-radius:0;padding:36px 0 32px;margin:0;border-bottom:1px solid #d9d8ce;align-items:start}
            header .eyebrow{font-size:12px;letter-spacing:.08em;margin:0 0 16px;color:#a25339}
            h1{font-size:34px;line-height:1.42;font-weight:500;max-width:22em;letter-spacing:0;margin:0 0 20px;color:#283226}
            .report-meta{display:flex;align-items:baseline;flex-wrap:wrap;gap:8px 20px;font-size:12px;line-height:1.7;color:#797c73}
            .report-meta a{color:#72766b;text-decoration:none;white-space:nowrap}.report-meta a:hover{color:#a25339;text-decoration:underline}
            .report-lead{font-size:16px;line-height:1.85;font-weight:400;max-width:37em;color:#535b4d;margin:24px 0 0;padding-left:16px;border-left:2px solid #abb39e}
            .video-cover{max-width:480px;margin:24px 0;align-self:start}
            .video-cover img{width:100%;height:auto;object-fit:contain;display:block;border-radius:6px;background:#e9e9df}
            header{display:block;border-bottom:0;padding-bottom:28px}
            header:has(.video-cover){display:block}
            .report-source{display:flex;gap:24px;flex-wrap:wrap;padding:16px 0;border-top:1px solid #deded4;border-bottom:1px solid #deded4;font-size:12px;color:#73796b;line-height:1.6}
            .report-method{font-size:12px;color:#73796b;margin:0 0 20px}.report-method summary{color:#73796b;font-size:12px}.report-method p{max-width:48em;line-height:1.9}
            .report-layout{max-width:760px;margin:0 auto;padding-top:32px}
            .report-toc{position:sticky;top:28px;display:grid;gap:4px;font-size:12px;line-height:1.65;padding-right:16px}
            .report-toc .label{font-size:11px;color:#92958b;margin:0 0 12px;font-weight:400}
            .report-toc a{color:#73786b;text-decoration:none;display:flex;gap:12px;padding:8px 0}
            .report-toc a:hover{color:#a25339}.report-toc a span{font-size:10px;font-variant-numeric:tabular-nums;color:#a4a79c;min-width:16px;padding-top:2px}
            .report-toc a[href='#creation']{margin-top:12px;border-top:1px solid #e4e4da;padding-top:16px;color:#a25339}
            .report-body{min-width:0}.report-body>section{padding:0 0 28px;margin:0 0 32px;background:transparent;border:0;border-radius:0;border-bottom:1px solid #d9d8ce}
            .report-body h2{font-size:13px;font-weight:400;color:#808577;margin:0 0 24px;line-height:1.6;letter-spacing:.035em}
            .topic{padding:28px 0;scroll-margin-top:28px;border-color:#e3e4da}
            .topic:first-of-type{padding-top:0}.topic:last-child{padding-bottom:4px}
            .topic-head{display:flex;align-items:baseline;gap:14px;flex-wrap:nowrap}
            .topic-number{font:18px Georgia,serif;color:#b3b8a8;font-variant-numeric:tabular-nums;flex-shrink:0}
            .topic h3{font-size:24px;line-height:1.5;color:#293326;font-weight:500}
            .topic .observation{font-size:15px;line-height:1.95;color:#555d4f;margin:16px 0 20px;max-width:40em}
            blockquote{background:transparent;border-left:2px solid #b07857;border-radius:0;padding:4px 0 4px 20px;margin:20px 0 12px;font-size:18px;line-height:1.8;color:#384331;max-width:36em}
            blockquote footer{margin-top:8px;font-size:11px;color:#73796b;line-height:1.6}
            .evidence-note{margin:10px 0 0;font-size:11px;color:#73796b;line-height:1.6}
            .topic-detail{margin-top:16px}.topic-detail>summary,.reply-context>summary{font-size:12px;color:#8a8f81}
            .reply-context p{font-size:13px;color:#73786b}.report-body>section>details{font-size:13px;margin-bottom:24px;color:#72796b}
            #creation{background:#f0f1e8;border:0;padding:24px 28px 12px;border-radius:4px}
            #creation h2{font-size:18px;color:#354332;letter-spacing:0;margin:0 0 16px;font-weight:500}
            .advice-row{display:grid;grid-template-columns:144px minmax(0,1fr);gap:24px;padding:20px 0;border-top:1px solid #dce0d2}
            .advice-row h3{font-size:13px;font-weight:500;color:#57634d;line-height:1.8}.advice-row p{margin:0;font-size:14px;line-height:1.9;color:#515c49}
            .report-body .trend-chart{padding:0}.report-body .trend-chart h3{font-size:20px;color:#354332}
            .report-footer{font-size:11px;line-height:1.85;color:#73796b;padding:0;max-width:58em}
            #creation,#playback{scroll-margin-top:28px}
            @media(max-width:760px){main{padding:20px 24px 32px}.masthead{gap:20px;padding-bottom:16px}.masthead>span:first-child{font-size:23px}header,header:has(.video-cover){display:flex;flex-direction:column;gap:24px;align-items:stretch;padding:28px 0}.video-cover{width:100%}h1{font-size:27px;line-height:1.45}.report-lead{font-size:15px;margin-top:20px}.report-source{font-size:11px;gap:8px 20px}.report-layout{display:block;padding-top:24px}.report-toc{position:static;grid-template-columns:repeat(2,minmax(0,1fr));padding:0 0 20px;margin-bottom:28px;border-bottom:1px solid #e1e2d7;gap:4px 20px}.report-toc>.label{grid-column:1/-1;margin-bottom:8px}.report-toc a{padding:6px 0;font-size:12px}.report-toc a[href='#creation']{margin:0;padding:6px 0;border:0}.report-body h2{font-size:12px;margin-bottom:20px}.topic h3{font-size:21px}.topic-head{gap:12px}.topic-number{font-size:16px}.topic{padding:24px 0}.topic .observation{font-size:15px;line-height:1.95;margin:12px 0 16px}blockquote{padding-left:16px;font-size:17px;line-height:1.85;margin:16px 0 10px}.advice-row{grid-template-columns:1fr;gap:8px;padding:16px 0}#creation{padding:20px 20px 8px}.report-meta{font-size:12px;gap:8px 16px}.report-footer{font-size:11px}}
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
