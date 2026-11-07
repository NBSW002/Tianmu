package com.tianmu.agent.preparation;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Bounded date spelling support; an unparsed explicit date must never mean an absent date. */
public final class TaskTimeText {
    private TaskTimeText() {}
    private static final Pattern CHINESE_DATE = Pattern.compile("(?<!\\d)(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号](?!\\d)");
    private static final Pattern ISO_DATE = Pattern.compile("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)");
    private static final Pattern UNPARSED = Pattern.compile(
            "[0-9〇零一二两三四五六七八九十]{1,4}\\s*年|[0-9〇零一二两三四五六七八九十]{1,2}\\s*月|[0-9〇零一二两三四五六七八九十]{1,2}\\s*[日号]"
            + "|(?<!\\d)\\d{4}\\s*[-/.]\\s*\\d{1,2}|(?<!\\d)\\d{1,2}/\\d{1,2}(?!\\d)"
            + "|去年|今年|前年|明年|昨日|今日|昨晚|昨夜|今早|今晚|今天|前天|明天|后天|上个?月|下个?月|本月|这个月|这周|上周|下周|本周|上午|下午|晚上|早上|凌晨|中午|傍晚"
            + "|(?:周|星期)[一二三四五六日天]|(?:近|过去|最近)\\s*[0-9一二两三四五六七八九十]+\\s*天");

    public static String normalize(String text) {
        var dates=CHINESE_DATE.matcher(text);
        String normalized=dates.replaceAll(match -> LocalDate.of(Integer.parseInt(match.group(1)),
                Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3))).toString());
        String remainder=ISO_DATE.matcher(normalized).replaceAll("");
        if(!ISO_DATE.matcher(normalized).find() && !normalized.contains("昨天"))
            remainder=remainder.replaceAll("(?:过去|最近|近)\\s*(?:7|七)\\s*天", "");
        if(UNPARSED.matcher(remainder).find())
            throw new IllegalArgumentException("请提供明确有效的完整日期或起止时间，例如2026-10-26；未能识别的时间不能按全量查询。");
        return normalized;
    }
}
