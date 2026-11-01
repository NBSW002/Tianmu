package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import java.util.*;

/** Coverage notices are derived from admitted evidence, never supplied by the answering model. */
public final class EvidencePresentation {
    private EvidencePresentation() {}
    public static void requireKind(TaskTypes.Specification specification, List<Evidence> evidence) {
        boolean supported = switch (specification.type()) {
            case "creator.draft" -> evidence.stream().anyMatch(e -> e.videoContent() != null);
            case "video.metric" -> evidence.stream().anyMatch(e -> e.data() != null);
            case "video.content" -> evidence.stream().anyMatch(e -> e.videoContent() != null);
            case "comments.analysis" -> evidence.stream().anyMatch(e -> e.comments() != null || e.feedback()!=null);
            case "danmaku.report", "danmaku.overview" -> evidence.stream().anyMatch(e -> e.analysis() != null && e.analysis().sources()==null || e.feedback()!=null);
            case "audience.report" -> evidence.stream().anyMatch(e -> e.analysis()!=null && e.analysis().sources()!=null || e.feedback()!=null);
            default -> true;
        };
        if (!supported) throw new AgentException("INVALID_EVIDENCE", "Answer lacks evidence required by the prepared task");
    }
    public static List<AnswerNote> notes(List<Evidence> evidence) {
        List<AnswerNote> notes = new ArrayList<>();
        for (Evidence value : evidence) {
            if(value.feedback()!=null)notes.add(new AnswerNote("视频"+value.feedback().request().query().videoId()+" · 反馈分析",
                    "基于固定快照完整处理所选来源；统计由代码核对，主题与建议由模型生成。作者发言单列，父评论语境不重复计数。视频采样不证明未覆盖的声音、动作或因果。",value.feedback().dataAsOf(),"Asia/Shanghai"));
            var comments = value.comments();
            if (comments != null) notes.add(new AnswerNote("视频" + comments.query().videoId() + " · 评论",
                    "统计所选时间内当前保留的评论和回复；作者发言单独计数。区间外的父评论仅供理解上下文，不计入区间互动。", comments.dataAsOf(), "Asia/Shanghai"));
            var data = value.data();
            if (data != null && "mysql:bullet:retained-persisted-v1".equals(data.source()))
                notes.add(new AnswerNote("视频" + data.videoId() + " · " + data.date(),
                        "按创建日期统计当前保留的已入库弹幕；不含已删除记录，也不代表全部已发送弹幕。", data.dataAsOf(), data.timezone()));
            var video = value.videoContent();
            if (video != null) {
                String audio = video.transcript().isBlank() ? "未提供可用音频转写，不能据此判断对白或音效。"
                        : "音频转写仅覆盖该区间，未提供逐句时间定位。";
                notes.add(new AnswerNote("视频" + video.videoId() + " · 播放区间" + video.startMs() + "–" + video.endMs() + "ms",
                        "来源版本：" + video.sourceVersion() + "；仅采样" + video.sampledAtMs().size() + "帧，位置：" + video.sampledAtMs()
                                + "ms；可能遗漏短暂事件，不能代表逐帧或连续动作分析。" + audio, video.analyzedAt(), "Asia/Shanghai"));
            }
        }
        return List.copyOf(notes);
    }
}
