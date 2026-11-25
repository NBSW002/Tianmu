package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentTypes.Answer;
import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.core.FeedbackAnalysis.Topic;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** A short answer from accepted topics and their own audience quotes. Playback density alone
 * cannot identify a problem. Prefer explicit viewer feedback over an unrelated popular topic. */
public final class FeedbackChatSummary {
    private FeedbackChatSummary() {}
    private static final long NEARBY_MS = 15_000;
    private static final Pattern FEEDBACK = Pattern.compile("没[听看]清|[听看]不[懂清]|不明白|看不见|太[快慢小大]|没声音|建议|希望|分不清|为什么|怎么|什么|吗[呀呢啊？?]?$|[?？]");
    private static final Pattern ISSUE_TOPIC = Pattern.compile("看不清|听不清|不清晰|过快|过慢|争议|分歧|纠正|改进|困惑|故障");
    private record Quote(String text, Source source, Long time) {}
    private record Finding(String title, String advice, List<Quote> quotes) {}
    private record Example(Finding finding, Quote first, Quote next, int distinctTexts) {
        boolean actionableTopic() { return ISSUE_TOPIC.matcher(finding.title()).find(); }
        boolean supported() { return next != null && (signals() > 0 || actionableTopic()); }
        int signals() {
            return (FEEDBACK.matcher(first.text()).find() ? 1 : 0)
                    + (next != null && FEEDBACK.matcher(next.text()).find() ? 1 : 0);
        }
    }

    public static Answer answer(String tool, Map<String,Object> receipt, String evidenceId) {
        String label = tool.equals("generate_feedback_report") ? "查看反馈报告" : "查看 HTML 报告";
        String prefix = Boolean.TRUE.equals(receipt.get("demo")) ? "【模拟数据】" : "";
        String text = prefix + receipt.getOrDefault("chat_summary", "反馈报告已生成并保存。")
                + "\n[" + label + "](" + receipt.get("url") + ")（需登录鉴权）";
        return new Answer(text, List.of(evidenceId));
    }

    static String fromNotes(List<Topic> notes, Map<String,List<FeedbackModels.Group>> leaves, long count) {
        var findings = notes.stream().map(t -> new Finding(t.title(), t.recommendation(), leaves.get(t.id()).stream()
                .filter(g -> !g.author()).map(g -> new Quote(g.text(), g.source(), g.playbackMs())).toList()));
        return summarize(findings, count == 0 ? "本次范围内没有观众反馈。" : "本次没有可引用的观众原话。");
    }

    public static String fromReport(FeedbackReportJson report) {
        var findings = Stream.concat(report.topics().stream(), report.otherTopics().stream())
                .map(t -> new Finding(t.title(), t.recommendation(), t.representatives().stream()
                        .map(r -> new Quote(r.text(), r.source(), r.playbackMs())).toList()));
        return summarize(findings, report.summary());
    }

    private static String summarize(Stream<Finding> findings, String empty) {
        Example timed = null, untimed = null;
        for (var it = findings.iterator(); it.hasNext();) {
            var finding = it.next();
            var window = new ArrayDeque<Quote>();
            var occurrences = new HashMap<String,Integer>();
            // Work within one accepted topic. Repeated copies add no corroboration.
            var quotes = finding.quotes().stream().filter(q -> !q.text().isBlank())
                    .sorted(Comparator.comparing(Quote::time, Comparator.nullsLast(Long::compare))).toList();
            for (var q : quotes) {
                if (q.source() != Source.DANMAKU || q.time() == null) {
                    untimed = prefer(untimed, new Example(finding, q, null, 1));
                    continue;
                }
                while (!window.isEmpty() && q.time() - window.getFirst().time() > NEARBY_MS) {
                    var oldest = window.removeFirst();
                    occurrences.computeIfPresent(oldest.text(), (key, n) -> n == 1 ? null : n - 1);
                }
                window.addLast(q);
                occurrences.merge(q.text(), 1, Integer::sum);
                var first = window.getFirst();
                var example = first.text().equals(q.text()) ? new Example(finding, q, null, 1)
                        : new Example(finding, first, q, occurrences.size());
                timed = prefer(timed, example);
            }
        }
        if (timed == null && untimed == null) return empty;
        var lines = new ArrayList<String>();
        if (timed == null || !timed.supported())
            lines.add("现有引文不足以定位问题集中出现的片段。");
        if (timed != null) lines.add(describe(timed));
        if (untimed != null) lines.add(describe(untimed));
        return String.join("\n\n", lines);
    }

    private static Example prefer(Example best, Example candidate) {
        if (best == null) return candidate;
        if (candidate.actionableTopic() != best.actionableTopic()) return candidate.actionableTopic() ? candidate : best;
        if (candidate.supported() != best.supported()) return candidate.supported() ? candidate : best;
        if (candidate.supported() && candidate.distinctTexts() != best.distinctTexts())
            return candidate.distinctTexts() > best.distinctTexts() ? candidate : best;
        if (candidate.signals() > best.signals()) return candidate;
        return best;
    }

    private static String describe(Example e) {
        var first = e.first();
        String position = first.source() == Source.COMMENT ? "评论（无播放时间）" : "弹幕（无播放时间）";
        if (first.source() == Source.DANMAKU && first.time() != null) {
            position = FeedbackReportRenderer.time(first.time());
            if (e.next() != null && first.time()/1000 != e.next().time()/1000)
                position += "–" + FeedbackReportRenderer.time(e.next().time());
        }
        String evidence = "「" + excerpt(first.text()) + "」";
        if (e.next() != null) evidence += "、「" + excerpt(e.next().text()) + "」";
        String advice = e.finding().advice().strip();
        int end = advice.indexOf('。');
        if (end >= 0) advice = advice.substring(0, end + 1);
        return position + " · " + literal(e.finding().title()) + "。观众原话" + evidence + "。"
                + (advice.isEmpty() ? "" : "下一版可参考报告建议：" + literal(advice));
    }

    private static String excerpt(String text) {
        int length = text.codePointCount(0, text.length());
        return literal(length <= 90 ? text : text.substring(0, text.offsetByCodePoints(0, 90)) + "…");
    }

    private static String literal(String text) {
        return text.replaceAll("\\R", " ").replaceAll("([\\\\`*_{}\\[\\]<>#$|~])", "\\\\$1");
    }
}
