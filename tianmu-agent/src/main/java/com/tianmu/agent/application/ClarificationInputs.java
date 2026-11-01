package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.DanmakuTarget;
import java.util.regex.Pattern;

/** Explicit resumption for the first supported capability, without inferring new privileges. */
public final class ClarificationInputs {
    private ClarificationInputs() {}
    public static String combine(String original, String reply) {
        return combine(original, reply, null);
    }
    public static String combine(String original, String reply, java.time.LocalDate originBusinessDate) {
        if (com.tianmu.agent.core.ConversationTopics.general(original)) return original + "\n用户补充：" + reply;
        if (com.tianmu.agent.preparation.DanmakuReportTask.requested(original)) return combineReport(original, reply, originBusinessDate);
        if (!original.contains("弹幕")) return original + "\n用户补充：" + reply;
        reply = normalizedTime(reply);
        String video = one(DanmakuTarget.ID_PATTERN, reply, 1);
        if (video == null) video = one(DanmakuTarget.ID_PATTERN, original, 1);
        boolean replyHasTime = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|昨天|\\d{1,2}:\\d{2}|\\d{1,2}点|(?:过去|最近|近)\\s*(?:7|七)\\s*天|全部|全量").matcher(reply).find();
        String time = replyHasTime ? reply : normalizedTime(original);
        time = DanmakuTarget.matcher(time).replaceAll("");
        // A missing video is still a clarification, but must not erase time qualifiers either.
        if (video == null) return "查询 " + time + "的弹幕数";
        validateTime("视频" + video + " " + time, originBusinessDate);
        String date = one("\\d{4}-\\d{2}-\\d{2}", time, 0);
        if (date == null && time.contains("昨天")) date = !replyHasTime && originBusinessDate != null ? originBusinessDate.minusDays(1).toString() : "昨天";
        if (date == null) return "查询视频" + video + " " + time + "的弹幕数";
        return "查询" + (video == null ? "" : "视频" + video) + (date == null ? "" : " " + date) + "的弹幕数";
    }
    public static java.util.Map<String, Object> confirmed(String query, java.time.LocalDate date) {
        if (com.tianmu.agent.preparation.DanmakuReportTask.requested(query)) {
            try { return com.tianmu.agent.preparation.DanmakuReportTask.specification(com.tianmu.agent.preparation.DanmakuReportTask.parse(query, date)).conditions(); }
            catch (RuntimeException ignored) { return java.util.Map.of(); }
        }
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        String time;
        try { time = com.tianmu.agent.preparation.TaskTimeText.normalize(query); }
        catch (IllegalArgumentException | java.time.DateTimeException invalid) { time = ""; }
        String video = unambiguous(DanmakuTarget.ID_PATTERN, query, 1), explicitDate = unambiguous("\\d{4}-\\d{2}-\\d{2}", time, 0);
        if (video != null) fields.put("video_id", video);
        if (explicitDate != null) {
            try { fields.put("date", java.time.LocalDate.parse(explicitDate).toString()); }
            catch (java.time.DateTimeException ignored) { /* Invalid dates remain missing conditions. */ }
        }
        else if (time.contains("昨天")) fields.put("date", date.minusDays(1).toString());
        if (query.contains("弹幕")) fields.put("metric", "bullet_count");
        return java.util.Map.copyOf(fields);
    }
    private static String combineReport(String original, String reply, java.time.LocalDate date) {
        reply = normalizedTime(reply);
        String video = one(DanmakuTarget.ID_PATTERN, reply, 1);
        if (video == null) video = one(DanmakuTarget.ID_PATTERN, original, 1);
        String time = reply;
        if (!Pattern.compile("\\d{4}-\\d{2}-\\d{2}|昨天").matcher(reply).find()) time = normalizedTime(original);
        if (time.contains("昨天") && date != null) time = time.replace("昨天", date.minusDays(1).toString());
        time = DanmakuTarget.matcher(time).replaceAll("");
        String combined = "分析视频" + (video == null ? "" : video) + " " + time + " 的弹幕并生成报告";
        try { com.tianmu.agent.preparation.DanmakuReportTask.parse(combined, date == null ? java.time.LocalDate.now(com.tianmu.agent.core.DanmakuTypes.ZONE) : date); }
        catch (RuntimeException e) { throw new AgentException("INVALID_REQUEST", "请重新提供完整的videoId和报告时间范围"); }
        return combined;
    }
    private static String normalizedTime(String text) {
        try { return com.tianmu.agent.preparation.TaskTimeText.normalize(text); }
        catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new AgentException("INVALID_REQUEST", "请提供有效的完整日期或起止时间，无法识别的时间不能按全量查询。");
        }
    }
    private static void validateTime(String text, java.time.LocalDate date) {
        try { com.tianmu.agent.preparation.DanmakuReportTask.parse(text, date == null ? java.time.LocalDate.now(com.tianmu.agent.core.DanmakuTypes.ZONE) : date); }
        catch (RuntimeException invalid) {
            throw new AgentException("INVALID_REQUEST", "请提供一个有效日期或完整起止时间，不能省略时间限定条件。");
        }
    }
    private static String unambiguous(String expression, String text, int group) {
        var matches = Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(text).results().toList();
        return matches.size() == 1 ? matches.get(0).group(group) : null;
    }
    private static String one(String expression, String text, int group) {
        var matches = Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(text).results().toList();
        if (matches.size() > 1) throw new AgentException("INVALID_REQUEST", "Provide exactly one video and date");
        return matches.isEmpty() ? null : matches.get(0).group(group);
    }
}
