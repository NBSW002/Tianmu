package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A video-only artifact. Feedback reports are rendered exclusively from completed feedback assets. */
@Component
public final class CreateVideoReportTool implements AgentTool {
    public String name(){return "create_video_report";}
    public ToolResult execute(ToolCall call,ToolContext context) {
        var contract=context.analysisContract();
        if(contract==null || !contract.reportRequired() || !contract.sources().equals(Set.of(AnalysisContract.Source.VIDEO)))
            throw new AgentException("TASK_CONSTRAINT_VIOLATION","A video-only report must match the frozen task");
        var evidence=context.evidence(ToolArguments.text(call,"evidence_id"));var video=evidence.videoContent();
        if(video==null)throw new AgentException("INVALID_EVIDENCE","Video evidence required");
        context.checkVideoQuery(video.videoId(),video.startMs(),video.endMs());
        var html=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>视频内容报告</title><main><h1>视频内容报告</h1><section><h2>视频内容证据</h2><p>");
        html.append(escape(video.summary())).append("</p><p>播放范围：").append(time(video.startMs())).append(" — ").append(time(video.endMs()))
                .append("；采样画面 ").append(video.sampledAtMs().size()).append(" 张。")
                .append(video.coverage().equals("SAMPLED_FRAMES")?"未分析音频。":"包含区间音频转写，非逐句时间标注。")
                .append("采样可能遗漏短暂事件，不能据此确认连续动作或因果。</p><ul>");
        for(var moment:video.moments())html.append("<li><strong>").append(time(moment.atMs())).append("</strong> · ").append(escape(moment.observation())).append("</li>");
        html.append("</ul>");
        if(!video.transcript().isBlank())html.append("<h3>音频转写</h3><p>").append(escape(video.transcript())).append("</p>");
        html.append("<details><summary>视频证据引用</summary><p>").append(escape(evidence.id())).append(" · ").append(escape(video.sourceVersion()))
                .append("</p></details></section><p>本报告只分析视频内容，未读取评论和弹幕。</p></main></html>");
        var report=context.saveReport(evidence.id(),html.toString().getBytes(StandardCharsets.UTF_8));
        return new ToolResult(name(),Map.of("artifact_id",report.artifactId(),"url",report.url(),"status","READY"),evidence.id(),
                new Answer("视频内容报告已生成。\n[查看 HTML 报告]("+report.url()+")（需登录鉴权）",List.of(evidence.id())));
    }
    private static String time(long ms){return String.format(Locale.ROOT,"%02d:%02d.%03d",ms/60000,ms/1000%60,ms%1000);}
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
