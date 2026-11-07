package com.tianmu.agent.preparation;

import com.tianmu.agent.core.DanmakuTarget;
import com.tianmu.agent.query.VideoSearchPort.Search;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative parser for metadata discovery, not permission or an executable analysis target. */
public final class VideoDiscovery {
    private VideoDiscovery(){}
    public static boolean requiresSelection(String text){
        return Pattern.compile("分析|统计|复盘|报告|智能评论|智能弹幕|(?:生成|写).{0,20}(?:评论|弹幕)").matcher(text).find();
    }
    /** Remove only publication qualifiers attached to a discovered object; retain feedback dates. */
    public static String analysisText(String text){
        return text.replaceAll("上周(?=(?:(?!评论|弹幕|反馈)[^，。！？])*?(?:视频|作品|投稿))", "")
                .replaceAll("昨天(?=(?:发布的|发的|投稿的))", "");
    }
    public static Optional<Search> request(String text,LocalDate date){
        if(DanmakuTarget.matcher(text).find() || !text.matches("(?s).*(视频|作品|投稿).*"))return Optional.empty();
        String query=null;
        for(String pattern:List.of("[《“\\\"]([^》”\\\"]{1,120})[》”\\\"]","(?:那个|那条)([^，。？\\s]{1,60}?)视频","(?:发布的|发的|的)([^，。？\\s]{1,60}?)视频")){
            var m=Pattern.compile(pattern).matcher(text);if(m.find()){query=m.group(1);break;}
        }
        LocalDate start=null,end=null;
        if(Pattern.compile("上周(?:(?!评论|弹幕|反馈)[^，。！？])*?(?:视频|作品|投稿)").matcher(text).find()){end=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));start=end.minusWeeks(1);}
        else if(text.contains("昨天") && text.matches("(?s).*昨天.*(?:发|发布|投稿).*")){start=date.minusDays(1);end=date;}
        if(query==null && start==null)return Optional.empty();
        return Optional.of(new Search(query==null?"":query,start,end,0,2));
    }
    public static boolean historicalReport(String text){return text.matches("(?s).*(?:上次|之前|历史|已有).*报告.*") || text.contains("rp_");}
}
