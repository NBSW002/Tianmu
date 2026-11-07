package com.tianmu.agent.feedback;

import java.util.*;

/** Script-free, portable charts derived exclusively from the accepted membership projection. */
final class FeedbackReportCharts {
    private FeedbackReportCharts(){}
    private static String e(Object value){return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static String time(long ms){return String.format(Locale.ROOT,"%02d:%02d",ms/60000,ms/1000%60);}
    private static String rangeTime(long ms){return time(ms)+(ms%1000==0?"":"."+String.format(Locale.ROOT,"%03d",ms%1000));}
    private static String number(double n){return String.format(Locale.ROOT,"%.2f",n);}
    static void render(StringBuilder h,FeedbackReportDocument document){
        var counts=document.asset().snapshot().counts();long audience=counts.total()-counts.authorComments()-counts.authorDanmaku();
        if(counts.total()==0)return;
        h.append("<section class='visual-overview' aria-label='反馈图表'><div class='chart-grid chart-grid-single'>");
        if(document.playback()!=null){playback(h,document.playback());h.append("<details class='distribution-detail'><summary>查看话题占比</summary>");}
        h.append("<figure class='chart-card topic-chart'><figcaption><p class='eyebrow'>讨论焦点</p><h3>哪些话题讨论最多</h3><p class='chart-note'>按观众反馈条数 · 不含作者发言</p></figcaption>");
        if(audience==0)h.append("<p class='chart-empty'>暂无观众反馈，作者说明已单独保留。</p>");
        else {
            h.append("<ol class='topic-bars'>");int index=0;
            for(var card:document.mainCards())bar(h,card.topic().title(),card.topic().messages()-card.topic().authorMessages(),audience,++index,false);
            if(document.cards().size()>FeedbackReportDocument.MAIN_TOPIC_LIMIT)bar(h,"其他话题",document.otherAudienceMessages(),audience,0,true);
            h.append("</ol><p class='chart-note'>占比以全部 ").append(audience).append(" 条观众反馈为分母，不代表观众人数或态度比例。</p>");
        }
        h.append("</figure>");if(document.playback()!=null)h.append("</details>");h.append("</div></section>");
    }
    private static void bar(StringBuilder h,String title,long count,long total,int index,boolean other){
        double percent=100.0*count/total;
        h.append("<li class='").append(other?"other-bar":"").append("'><div class='bar-heading'><span class='bar-name'>");
        if(!other)h.append("<span class='bar-index'>").append(String.format(Locale.ROOT,"%02d",index)).append("</span>");
        h.append(e(title)).append("</span><span class='bar-value'>").append(count).append(" 条 <small>").append(String.format(Locale.ROOT,"%.1f",percent)).append("%</small></span></div><div class='bar-track' aria-hidden='true'><span style='width:").append(number(percent)).append("%'></span></div></li>");
    }
    static void playback(StringBuilder h,FeedbackPlaybackTrend trend){
        h.append("<figure class='chart-card trend-chart'><figcaption><p class='eyebrow'>播放位置</p><h3>弹幕集中在哪些片段</h3>");
        if(trend.plotted()==0){h.append("<p class='chart-empty'>暂无可绘制的弹幕播放位置。</p>");if(trend.omitted()>0)h.append("<p class='chart-note'>").append(trend.omitted()).append(" 条未定位，未画入趋势。</p>");h.append("</figcaption></figure>");return;}
        String interval=trend.bucketMs()<1000?trend.bucketMs()+"毫秒":trend.bucketMs()%60000==0?trend.bucketMs()/60000+"分钟":trend.bucketMs()/1000+"秒";
        long peak=Collections.max(trend.counts());int peakIndex=trend.counts().indexOf(peak);
        h.append("<p class='chart-note'>每 ").append(interval).append("一段 · 按播放位置统计，非观看人数</p></figcaption><div class='peak-callout'><span class='peak-dot'></span>峰值 <strong>").append(peak).append(" 条</strong><span>").append(rangeTime(trend.binStart(peakIndex))).append("–").append(rangeTime(trend.binEnd(peakIndex))).append("</span></div>");
        var line=new StringBuilder();int n=trend.counts().size();long ceiling=Math.max(2,peak);if(ceiling%2!=0 && ceiling<Long.MAX_VALUE)ceiling++;
        for(int i=0;i<n;i++){
            double midpoint=trend.binStart(i)+(trend.binEnd(i)-trend.binStart(i))/2.0;
            double x=640*(midpoint-trend.startMs())/(trend.endMs()-trend.startMs()),y=172-164.0*trend.counts().get(i)/ceiling;
            line.append(i==0?"M":" L").append(number(x)).append(' ').append(number(y));
        }
        if(n==1){double y=172-164.0*peak/ceiling;line=new StringBuilder("M0 "+number(y)+" L640 "+number(y));}
        double firstX=n==1?0:640*((trend.binStart(0)+(trend.binEnd(0)-trend.binStart(0))/2.0)-trend.startMs())/(trend.endMs()-trend.startMs());
        double lastX=n==1?640:640*((trend.binStart(n-1)+(trend.binEnd(n-1)-trend.binStart(n-1))/2.0)-trend.startMs())/(trend.endMs()-trend.startMs());
        h.append("<div class='trend-plot'><div class='y-axis' aria-hidden='true'><span>").append(ceiling).append("</span><span>").append(ceiling/2).append("</span><span>0</span></div><svg class='playback-chart' viewBox='0 0 640 180' preserveAspectRatio='none' role='img' aria-labelledby='playback-title playback-description'><title id='playback-title'>弹幕播放位置分布</title><desc id='playback-description'>共绘制 ").append(trend.plotted()).append(" 条弹幕，每").append(interval).append("一段，峰值 ").append(peak).append(" 条；完整分段计数见下方数据表。</desc><path class='plot-grid' d='M0 8H640 M0 90H640 M0 172H640'/><path fill='#c96442' fill-opacity='.14' d='").append(line).append(" L").append(number(lastX)).append(" 172 L").append(number(firstX)).append(" 172 Z'/><path class='trend-line' vector-effect='non-scaling-stroke' d='").append(line).append("'/></svg><div class='x-axis' aria-hidden='true'>");
        for(int i=0;i<5;i++)h.append("<span>").append(time(trend.startMs()+(long)((trend.endMs()-trend.startMs())*(i/4.0)))).append("</span>");
        h.append("</div></div><p class='chart-note'>已定位 ").append(trend.plotted()).append(" 条");
        if(trend.omitted()>0)h.append(" · ").append(trend.omitted()).append(" 条位置缺失或超出范围，未绘制");
        if(!trend.knownRange())h.append(" · 范围截至已记录的最晚位置，未取得视频时长");
        h.append("。含作者发言；末段可能不足一个完整分段。</p><details class='chart-data'><summary>查看分段数据</summary><div class='table-scroll'><table><thead><tr><th scope='col'>播放区间（左闭右开）</th><th scope='col'>弹幕数</th></tr></thead><tbody>");
        for(int i=0;i<n;i++)h.append("<tr><th scope='row'>").append(rangeTime(trend.binStart(i))).append("–").append(rangeTime(trend.binEnd(i))).append("</th><td>").append(trend.counts().get(i)).append("</td></tr>");
        h.append("</tbody></table></div></details></figure>");
    }
    static final String STYLE="""
        .chart-grid{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(0,1fr);gap:22px;align-items:start}.chart-grid-single{grid-template-columns:1fr}.chart-card{margin:0;min-width:0;padding:22px;background:#faf9f5;border:1px solid #e8e6dc;border-radius:16px}.chart-card h3{font-size:18px;line-height:1.5}.chart-card .eyebrow{margin:0 0 6px}.chart-note{font-size:12px;color:#5e5d59;line-height:1.65;margin:10px 0}.peak-callout{display:flex;align-items:center;gap:8px;font-size:12px;color:#5e5d59;margin:20px 0 14px}.peak-callout strong{color:#a55237}.peak-dot{width:6px;height:6px;background:#c96442;border-radius:50%}.trend-plot{--plot-height:180px;position:relative;padding-left:34px}.playback-chart{display:block;width:100%;height:var(--plot-height);overflow:visible}.plot-grid{fill:none;stroke:#e8e6dc;stroke-width:1;stroke-dasharray:3 5;vector-effect:non-scaling-stroke}.trend-line{fill:none;stroke:#c96442;stroke-width:2.5;stroke-linecap:round;stroke-linejoin:round}.y-axis{position:absolute;left:0;top:8px;height:calc(var(--plot-height) - 16px);display:flex;flex-direction:column;justify-content:space-between;text-align:right;width:26px}.x-axis{display:flex;justify-content:space-between;margin-top:8px}.x-axis,.y-axis{font-size:10px;color:#87867f;font-variant-numeric:tabular-nums;line-height:1}.topic-bars{list-style:none;padding:0;margin:22px 0}.topic-bars li{margin:0 0 17px}.bar-heading{display:flex;align-items:baseline;justify-content:space-between;gap:10px;margin-bottom:7px;font-size:12px}.bar-name{min-width:0;line-height:1.6}.bar-index{font-size:10px;color:#87867f;margin-right:7px}.bar-value{flex-shrink:0;color:#4d4c48;font-variant-numeric:tabular-nums}.bar-value small{font-size:10px;margin-left:4px;color:#87867f}.bar-track{height:6px;border-radius:4px;background:#e8e6dc;overflow:hidden}.bar-track span{display:block;height:100%;min-width:0;background:#c96442;border-radius:4px}.other-bar .bar-track span{background:#b0aea5}.distribution-detail{margin:0}.distribution-detail>summary{font-size:13px}.distribution-detail[open]>.chart-card{margin-top:14px}.chart-empty{font-size:14px;color:#5e5d59;padding:38px 0}.chart-data{font-size:12px;margin-top:14px}.chart-data summary{color:#87867f}.other-topics{padding:16px 18px;border-radius:12px;background:#faf9f5;font-size:13px}.other-topics li{margin:8px 0}.daily-data{margin:0}.daily-data>summary{font-size:15px}.topic-coverage{font-size:13px;color:#5e5d59}@media(max-width:760px){.chart-grid{grid-template-columns:1fr}.chart-card{padding:18px}.trend-plot{--plot-height:150px}.bar-heading{font-size:13px}.bar-value small{font-size:11px}.x-axis,.y-axis{font-size:11px}}
        """;
}
