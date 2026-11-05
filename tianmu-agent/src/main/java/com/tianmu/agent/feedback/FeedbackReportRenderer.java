package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Trusted presentation code. Model text and source quotations are always escaped. */
public final class FeedbackReportRenderer {
    private FeedbackReportRenderer(){}
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static String e(Object value){return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static String time(long ms){return String.format(Locale.ROOT,"%02d:%02d",ms/60000,ms/1000%60);}
    private static String preview(String s,int max){return s.substring(0,s.offsetByCodePoints(0,Math.min(max,s.codePointCount(0,s.length()))));}
    private static String lead(String text){int end=text.indexOf('。');return end>=0 && end<180?text.substring(0,end+1):preview(text,180)+(text.codePointCount(0,text.length())>180?"…":"");}
    private static void paragraphs(StringBuilder h,String text){for(String p:text.split("\\n\\s*\\n"))if(!p.isBlank())h.append("<p>").append(e(p)).append("</p>");}
    private static void link(StringBuilder h,long video,long ms,String label){h.append("<a target=\"_blank\" rel=\"noopener noreferrer\" href=\"/video/").append(video).append("?t=").append(ms/1000.0).append("\">").append(e(label)).append(" ↗</a>");}
    public static byte[] render(FeedbackReportDocument document){
        var asset=document.asset();var counts=asset.snapshot().counts();var query=asset.request().query();long video=query.videoId();
        var h=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>观众反馈报告</title><style>");
        h.append(STYLE).append("</style></head><body><main><header><p class=\"eyebrow\">观众反馈 · 视频复盘</p><h1>观众都在聊什么</h1><p class=\"muted\">");
        if(query.allTime())h.append("全部已保留反馈 · 截至 ").append(e(DATE.format(query.end())));
        else h.append(e(DATE.format(query.start()))).append(" — ").append(e(DATE.format(query.end())));
        h.append(" · 北京时间</p><div class=\"metrics\">");
        if(asset.model().equals("demo-scripted-feedback"))h.append("<p>模拟数据，仅用于工程演示</p>");
        if(asset.request().sources().contains(Source.DANMAKU))metric(h,counts.danmaku(),"条弹幕");
        if(asset.request().sources().contains(Source.COMMENT))metric(h,counts.comments(),"条评论及回复");
        metric(h,counts.uniqueAudience(),"位可识别观众");
        h.append("</div><p class=\"muted\">已处理所选范围全部 ").append(counts.total()).append(" 条互动，包含 ").append(counts.authorComments()+counts.authorDanmaku()).append(" 条作者发言。</p></header>");
        h.append("<section class=\"takeaway\"><p class=\"eyebrow\">先看结论</p><h2>");
        if(document.cards().isEmpty())h.append("本次没有反馈记录</h2><p>指定来源和范围内暂无可归纳的反馈，不代表视频其他时间没有互动。</p>");
        else{
            h.append("主要讨论集中在这些方面</h2><ul class=\"findings\">");
            for(var card:document.cards().stream().limit(3).toList())h.append("<li><strong>").append(e(card.topic().title())).append("</strong><p>").append(e(lead(card.topic().observation()))).append("</p></li>");
            h.append("</ul><p class=\"muted\">按相关观众反馈量排列；各话题中的不同偏好与证据见下文，不能据条数推断留存或整体受众态度。</p>");
        }
        h.append("</section>");
        if(asset.video()!=null){h.append("<section><h2>这段视频的内容</h2>");paragraphs(h,asset.video().summary());h.append("<p class=\"muted\">依据 ").append(asset.video().sampledAtMs().size()).append(" 个采样位置；静帧不代表完整连续过程。");
            if(asset.video().transcript().isBlank())h.append("未提供音频转录，配乐与收音评价仅来自反馈，尚未独立核实。");h.append("</p></section>");}
        h.append("<section aria-labelledby=\"topics-title\"><h2 id=\"topics-title\">观众在讨论什么</h2>");int index=0;
        for(var card:document.cards()){
            var t=card.topic();h.append("<article class=\"topic\" id=\"topic-").append(++index).append("\"><div class=\"topic-head\"><span class=\"number\">").append(String.format(Locale.ROOT,"%02d",index)).append("</span><h3>").append(e(t.title())).append("</h3><span class=\"badge\">").append(t.messages()-t.authorMessages()).append(" 条观众反馈</span></div><p class=\"muted\">");
            if(asset.request().sources().contains(Source.DANMAKU))h.append("弹幕 ").append(card.danmaku()).append(" 条 · ");
            if(asset.request().sources().contains(Source.COMMENT))h.append("评论及回复 ").append(card.comments()).append(" 条 · ");
            h.append("其中作者 ").append(t.authorMessages()).append(" 条</p>");paragraphs(h,t.observation());
            if(!card.quotes().isEmpty())h.append("<p class=\"label\">原话与来源</p>");
            for(var q:card.quotes()){
                boolean longer=q.text().codePointCount(0,q.text().length())>180;
                if(longer)h.append("<details><summary>展开较长原话</summary>");
                h.append("<blockquote>").append(e(preview(q.text(),800)));
                if(q.text().codePointCount(0,q.text().length())>800)h.append("（节选，完整原文保存在授权证据中）");
                h.append("<footer>").append(q.author()?"作者":"观众").append(" · ").append(q.source()==Source.DANMAKU?"弹幕":"评论");
                if(q.source()==Source.DANMAKU && q.playbackMs()!=null){h.append(" · ");link(h,video,q.playbackMs(),time(q.playbackMs()));}
                h.append("</footer></blockquote>");if(longer)h.append("</details>");
                if(!q.replyContext().isBlank())h.append("<details class=\"reply-context\"><summary>查看回复上文</summary><p>").append(e(preview(q.replyContext(),600))).append(q.replyContext().codePointCount(0,q.replyContext().length())>600?"（上文节选）":"").append("</p></details>");
            }
            if(!card.moments().isEmpty()){
                h.append("<div class=\"context\"><p class=\"label\">原话播放位置附近的采样画面</p>");
                for(var m:card.moments()){h.append("<p>");link(h,video,m.atMs(),time(m.atMs()));h.append(" ").append(e(m.observation())).append("</p>");}
                h.append("<small>仅提供附近画面语境，不证明反馈中的声音、连续动作或技术问题成立。</small></div>");
            }
            if(!t.recommendation().isBlank()){h.append("<div class=\"action\"><p class=\"label\">可以怎么做</p>");paragraphs(h,t.recommendation());h.append("</div>");}
            h.append("</article>");
        }
        h.append("</section>");
        if(!document.daily().isEmpty()){
            h.append("<section><h2>观众什么时候讨论得最多</h2><p class=\"muted\">按反馈发送日期统计，与视频播放位置不同。</p><div class=\"table-scroll\"><table><thead><tr><th scope=\"col\">日期</th><th scope=\"col\">反馈记录</th></tr></thead><tbody>");
            document.daily().forEach((day,n)->h.append("<tr><th scope=\"row\">").append(e(day)).append("</th><td>").append(n).append("</td></tr>"));h.append("</tbody></table></div></section>");
        }
        h.append("<details class=\"method\"><summary>统计口径与完整分析记录</summary><p>只包含所选范围内当前保留的入库记录，结束时刻不计入。父评论仅作语境，不重复计数；跨来源观众已去重。原话是阅读例子，展示去重不改变分析成员和统计。结构覆盖不等于所有语义判断正确。</p><p>视频 ").append(video).append(" · 数据读取时间 ").append(e(DATE.format(asset.snapshot().dataAsOf().atZone(ZONE)))).append("。</p>");
        if(!document.notes().isEmpty()){
            h.append("<p>以下是保留的局部观察，可能相互重叠，不能叠加计数。完整原文与归属保存在私有证据中。</p>");
            for(var t:document.notes()){h.append("<details><summary>").append(e(t.title())).append("</summary>");paragraphs(h,t.observation());paragraphs(h,t.recommendation());h.append("</details>");}
        }
        h.append("</details><footer class=\"page-footer\">观众反馈报告 · <a target=\"_blank\" rel=\"noopener noreferrer\" href=\"/video/").append(video).append("\">返回视频 ↗</a></footer></main></body></html>");
        return h.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void metric(StringBuilder h,long value,String label){h.append("<div><strong>").append(value).append("</strong><span>").append(label).append("</span></div>");}
    private static final String STYLE="""
        :root{color-scheme:light}*{box-sizing:border-box}body{margin:0;background:#f7f5f8;color:#302d36;font:16px/1.75 system-ui,sans-serif;overflow-wrap:anywhere}main{max-width:1040px;margin:auto;padding:40px 28px}header,section,.method{background:#fff;border:1px solid #e9e3ec;border-radius:20px;margin:0 0 24px;padding:30px}header{background:linear-gradient(125deg,#f0e8f2,#faf7fb)}h1{font-size:36px;line-height:1.3;margin:10px 0 12px;letter-spacing:-1px}h2{font-size:23px;line-height:1.5;margin:0 0 18px}h3{font-size:20px;line-height:1.5;margin:0;flex:1;min-width:0}p{margin:10px 0 16px;white-space:pre-wrap}a{color:#906380;text-underline-offset:4px}a:focus-visible,summary:focus-visible{outline:2px solid #906380;outline-offset:4px}.eyebrow,.label{color:#906380;font-size:13px;font-weight:650;letter-spacing:.04em}.muted,small,blockquote footer{font-size:13px;color:#77717e}.metrics{display:flex;flex-wrap:wrap;gap:28px;margin:25px 0 18px}.metrics div{display:grid;gap:0}.metrics strong{font-size:30px;line-height:1.3}.metrics span{font-size:13px;color:#77717e}.findings{padding-left:22px}.findings li{margin:8px 0}.topic{border-top:1px solid #eee8f0;padding:26px 0;scroll-margin-top:20px}.topic:first-of-type{border-top:0;padding-top:8px}.topic:last-child{padding-bottom:0}.topic-head{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap}.number{color:#a68aab;font-size:14px;font-variant-numeric:tabular-nums}.badge{font-size:12px;background:#f4eff6;color:#786780;padding:4px 9px;border-radius:20px;white-space:nowrap}blockquote{border-left:3px solid #cbb4cc;background:#faf7fb;padding:14px 18px;margin:12px 0;font-size:15px}blockquote footer{margin-top:8px}.context,.action{padding:16px 20px;border-radius:12px;margin-top:18px}.context{background:#f6f8f7}.action{background:#f6f1f7}.context p:last-of-type,.action p:last-child{margin-bottom:0}.label{margin-top:0}details{margin:12px 0}summary{cursor:pointer;color:#7b617d}.method{font-size:14px;background:transparent}.method>summary{font-weight:600}.reply-context{font-size:13px}.table-scroll{overflow-x:auto}table{width:100%;border-collapse:collapse;text-align:left;font-size:14px}td,th{padding:10px 12px;border-bottom:1px solid #eee8f0}th{font-weight:500}.page-footer{font-size:13px;color:#77717e;padding:0 4px 24px}@media(max-width:600px){main{padding:16px 12px}header,section,.method{padding:20px 18px;border-radius:15px;margin-bottom:16px}h1{font-size:28px}h2{font-size:21px}h3{font-size:18px}.metrics{gap:22px}.badge{margin-left:26px}blockquote{padding:12px}.context,.action{padding:14px}}
        """;
}
