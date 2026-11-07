package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Trusted presentation code. Model text and source quotations are always escaped. */
public final class FeedbackReportRenderer {
    private FeedbackReportRenderer(){}
    static String styles(){return STYLE;}
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static String e(Object value){return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static String time(long ms){return String.format(Locale.ROOT,"%02d:%02d",ms/60000,ms/1000%60);}
    private static String preview(String s,int max){return s.substring(0,s.offsetByCodePoints(0,Math.min(max,s.codePointCount(0,s.length()))));}
    private static String brief(String text){
        String plain=text.replaceAll("\\s+"," ").strip();
        int limit=plain.offsetByCodePoints(0,Math.min(96,plain.codePointCount(0,plain.length())));
        for(int i=0;i<limit;i++)if("。；;！？".indexOf(plain.charAt(i))>=0 && i>=10)return plain.substring(0,i)+(plain.charAt(i)=='；' || plain.charAt(i)==';'?"。":String.valueOf(plain.charAt(i)));
        if(plain.codePointCount(0,plain.length())<=80)return plain;
        return ""; // Never show a severed condition or half a sentence as a creator's next action.
    }
    private static void paragraphs(StringBuilder h,String text){for(String p:text.split("\\n\\s*\\n"))if(!p.isBlank())h.append("<p>").append(e(p)).append("</p>");}
    private static void link(StringBuilder h,long video,long ms,String label){h.append("<a target=\"_blank\" rel=\"noopener noreferrer\" href=\"/video/").append(video).append("?t=").append(ms/1000.0).append("\">").append(e(label)).append(" ↗</a>");}
    public static byte[] render(FeedbackReportDocument document){
        var asset=document.asset();var counts=asset.snapshot().counts();var query=asset.request().query();long video=query.videoId();
        var h=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>观众反馈报告</title><style>");
        h.append(STYLE).append(FeedbackReportCharts.STYLE).append("</style></head><body><main><header><h1>这条视频，观众怎么说</h1><p class=\"muted\">");
        if(query.allTime())h.append("全部已保留反馈 · 截至 ").append(e(DATE.format(query.end())));
        else h.append(e(DATE.format(query.start()))).append(" — ").append(e(DATE.format(query.end())));
        h.append(" · 北京时间</p><div class=\"metrics\">");
        if(asset.model().equals("demo-scripted-feedback"))h.append("<p>模拟数据，仅用于工程演示</p>");
        if(asset.request().sources().contains(Source.DANMAKU))metric(h,counts.danmaku(),"条弹幕");
        if(asset.request().sources().contains(Source.COMMENT))metric(h,counts.comments(),"条评论及回复");
        metric(h,counts.uniqueAudience(),"位可识别观众");
        h.append("</div></header>");
        h.append("<section aria-labelledby=\"topics-title\"><h2 id=\"topics-title\">");
        if(document.cards().isEmpty())h.append("本次没有反馈记录</h2><p>指定来源和范围内暂无可归纳的反馈。</p>");
        else h.append("讨论最多的 ").append(document.mainCards().size()).append(" 个话题</h2>");
        int index=0;
        if(document.cards().size()>FeedbackReportDocument.MAIN_TOPIC_LIMIT)h.append("<p class='topic-coverage'>前五话题覆盖 ").append(document.mainCards().stream().mapToLong(c->c.topic().messages()-c.topic().authorMessages()).sum()).append(" 条观众反馈，其他 ").append(document.otherAudienceMessages()).append(" 条可在下方展开。</p>");
        for(var card:document.mainCards()){
            var t=card.topic();h.append("<article class=\"topic\" id=\"topic-").append(++index).append("\"><div class=\"topic-head\"><span class=\"number\">").append(String.format(Locale.ROOT,"%02d",index)).append("</span><h3>").append(e(t.title())).append("</h3><span class=\"badge\">").append(t.messages()-t.authorMessages()).append(" 条观众反馈</span></div>");
            String advice=brief(t.recommendation());
            if(!advice.isBlank())h.append("<p class=\"action-preview\"><span>").append(advice.equals(t.recommendation().strip())?"建议":"建议摘录").append("</span>").append(e(advice)).append("</p>");
            h.append("<details class=\"topic-detail\"><summary>观众原话与完整建议</summary><p class=\"muted\">");
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
            h.append("</details></article>");
        }
        if(document.cards().size()>FeedbackReportDocument.MAIN_TOPIC_LIMIT){
            h.append("<details class='other-topics'><summary>其他话题 · 完整分析已保留</summary><ul>");
            for(var card:document.cards().stream().skip(FeedbackReportDocument.MAIN_TOPIC_LIMIT).toList())h.append("<li>").append(e(card.topic().title())).append(" · ").append(card.topic().messages()-card.topic().authorMessages()).append(" 条观众反馈</li>");
            h.append("</ul><p>对应观察及建议见下方完整分析记录；这些话题没有被删除或并入主话题。</p></details>");
        }
        h.append("</section>");
        FeedbackReportCharts.render(h,document);
        h.append("<details class=\"method\"><summary>完整反馈与统计说明</summary><p>已处理所选范围全部 ").append(counts.total()).append(" 条互动，包含 ").append(counts.authorComments()+counts.authorDanmaku()).append(" 条作者发言。只包含当前保留的入库记录，结束时刻不计入。父评论仅作语境，不重复计数；跨来源观众已去重。原话是阅读例子，展示去重不改变分析成员和统计。结构覆盖不等于所有语义判断正确。</p><p>视频 ").append(video).append(" · 数据读取时间 ").append(e(DATE.format(asset.snapshot().dataAsOf().atZone(ZONE)))).append("。</p>");
        if(asset.video()!=null){h.append("<details><summary>这段视频的内容与画面依据</summary>");paragraphs(h,asset.video().summary());h.append("<p class=\"muted\">依据 ").append(asset.video().sampledAtMs().size()).append(" 个采样位置；静帧不代表完整连续过程。");if(asset.video().transcript().isBlank())h.append("未提供音频转录，配乐与收音评价仅来自反馈，尚未独立核实。");h.append("</p></details>");}
        if(!document.daily().isEmpty()){
            h.append("<details class=\"daily-data\"><summary>按发送日期查看反馈</summary><p class=\"muted\">按反馈发送日期统计，与视频播放位置不同。</p><div class=\"table-scroll\"><table><thead><tr><th scope=\"col\">日期</th><th scope=\"col\">反馈记录</th></tr></thead><tbody>");
            document.daily().forEach((day,n)->h.append("<tr><th scope=\"row\">").append(e(day)).append("</th><td>").append(n).append("</td></tr>"));h.append("</tbody></table></div></details>");
        }
        if(!document.notes().isEmpty()){
            h.append("<p>以下是保留的局部观察，可能相互重叠，不能叠加计数。完整原文与归属保存在私有证据中。</p>");
            for(var t:document.notes()){h.append("<details><summary>").append(e(t.title())).append("</summary>");paragraphs(h,t.observation());paragraphs(h,t.recommendation());h.append("</details>");}
        }
        h.append("</details><footer class=\"page-footer\">观众反馈报告 · <a target=\"_blank\" rel=\"noopener noreferrer\" href=\"/video/").append(video).append("\">返回视频 ↗</a></footer></main></body></html>");
        return h.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void metric(StringBuilder h,long value,String label){h.append("<div><strong>").append(value).append("</strong><span>").append(label).append("</span></div>");}
    private static final String STYLE="""
        :root{color-scheme:light}*{box-sizing:border-box}body{margin:0;background:#f5f4ed;color:#141413;font:16px/1.75 'PingFang SC','Microsoft YaHei UI','Microsoft YaHei',system-ui,sans-serif;overflow-wrap:anywhere}main{max-width:1040px;margin:auto;padding:40px 28px}header,section,.method{background:#faf9f5;border:1px solid #e8e6dc;border-radius:20px;margin:0 0 24px;padding:30px}header{background:#e8e6dc;padding:24px}header>.muted{margin:6px 0 12px}header .metrics{margin-top:10px}h1{font-weight:500;font-size:28px;line-height:1.3;margin:10px 0 12px;letter-spacing:-1px}h2{font-weight:500;font-size:23px;line-height:1.5;margin:0 0 18px}h3{font-weight:500;font-size:20px;line-height:1.5;margin:0;flex:1;min-width:0}p{margin:10px 0 16px;white-space:pre-wrap}a{color:#a55237;text-underline-offset:4px}a:focus-visible,summary:focus-visible{outline:2px solid #a55237;outline-offset:4px}.eyebrow,.label{color:#a55237;font-size:13px;font-weight:650;letter-spacing:.04em}.muted,small,blockquote footer{font-size:13px;color:#5e5d59}.metrics{display:flex;flex-wrap:wrap;gap:28px;margin:16px 0 0}.metrics div{display:grid;gap:0}.metrics strong{font-size:23px;line-height:1.3}.metrics span{font-size:13px;color:#5e5d59}.action-preview{font-size:14px;line-height:1.7;margin:10px 0 8px}.action-preview>span{font-size:12px;color:#a55237;margin-right:10px}.topic-detail{margin:8px 0 0}.topic-detail>summary{font-size:12px;color:#87867f}.topic-detail[open]{padding-top:8px}.topic-detail[open]>summary{margin-bottom:16px}.topic{border-top:1px solid #e8e6dc;padding:18px 0;scroll-margin-top:20px}.topic:first-of-type{border-top:0;padding-top:8px}.topic:last-child{padding-bottom:0}.topic-head{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap}.number{color:#87867f;font-size:14px;font-variant-numeric:tabular-nums}.badge{font-size:12px;background:#e8e6dc;color:#4d4c48;padding:4px 9px;border-radius:20px;white-space:nowrap}blockquote{border-left:3px solid #c96442;background:#faf9f5;padding:14px 18px;margin:12px 0;font-size:15px}blockquote footer{margin-top:8px}.context,.action{padding:16px 20px;border-radius:12px;margin-top:18px}.context{background:#e9eee4}.action{background:#f1e2d6}.context p:last-of-type,.action p:last-child{margin-bottom:0}.label{margin-top:0}details{margin:12px 0}summary{cursor:pointer;color:#5e5d59}.method{font-size:14px;background:transparent}.method>summary{font-weight:600}.reply-context{font-size:13px}.table-scroll{overflow-x:auto}table{width:100%;border-collapse:collapse;text-align:left;font-size:14px}td,th{padding:10px 12px;border-bottom:1px solid #e8e6dc}th{font-weight:500}.page-footer{font-size:13px;color:#5e5d59;padding:0 4px 24px}@media(max-width:600px){main{padding:16px 12px}header,section,.method{padding:20px 18px;border-radius:15px;margin-bottom:16px}h1{font-size:28px}h2{font-size:21px}h3{font-size:18px}.metrics{gap:22px}.badge{margin-left:26px}blockquote{padding:12px}.context,.action{padding:14px}}
        """;
}
