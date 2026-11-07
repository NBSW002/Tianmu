package com.tianmu.agent.demo;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.DanmakuTarget;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.query.BusinessQueryPort;
import org.springframework.context.annotation.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.CallControl;

/** Offline fixtures only. No tianmu database, remote credentials or LLM is used. */
@Configuration
@Profile("demo")
public class DemoAdapters {
    @Bean @Profile("!business") com.tianmu.agent.query.VideoSearchPort demoVideoSearch(){
        return (q,owner,control)->{
            if(control!=null)control.checkpoint();
            var items=owner.videoIds().stream().sorted().map(id->new com.tianmu.agent.query.VideoSearchPort.Candidate(Long.toString(id),"小鸭日常 · 演示视频",31000,
                    LocalDate.now(ZoneId.of("Asia/Shanghai")).minusWeeks(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()))
                    .filter(v->v.title().contains(q.query())).filter(v->q.startDate()==null || !v.publishedAt().isBefore(q.startDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()) && v.publishedAt().isBefore(q.endDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant())).toList();
            int from=Math.min(q.offset(),items.size()),to=Math.min(items.size(),from+q.limit());return new com.tianmu.agent.query.VideoSearchPort.Page(items.subList(from,to),to,to<items.size());
        };
    }
    /** Offline fixtures for three independent preparation branches. */
    @Bean @Profile("!deepseek") PreparationModelPort demoPreparationModel() {
        return new PreparationModelPort() {
            public Intent intent(Input input, CallControl control) {
                control.checkpoint();
                try {
                    String type=TaskMeaning.defaults().resolve(input.query(),input.businessDate(),input.requestedAt(),input.memory()).type();
                    return switch(type) {
                        case "video.metric" -> Intent.VIDEO_METRIC;
                        case "danmaku.overview", "danmaku.report", "audience.report", "comments.analysis" -> Intent.DANMAKU_REPORT;
                        default -> input.query().contains("解释") ? Intent.KNOWLEDGE : Intent.OTHER;
                    };
                } catch(AgentException invalid) { return Intent.OTHER; }
            }
            public Emotion emotion(Input input, CallControl control) {
                control.checkpoint();
                return input.query().contains("太差") || input.query().contains("生气") ? Emotion.NEGATIVE : Emotion.NEUTRAL;
            }
            public Understanding understand(Input input, CallControl control) {
                control.checkpoint();
                String text = input.query();
                try {
                    var task=TaskMeaning.defaults().resolve(text,input.businessDate(),input.requestedAt(),input.memory());
                    return Understanding.proceed(task.conditions(),text.strip().replaceAll("\\s+", " "));
                } catch(AgentException invalid) {
                    if(!"AMBIGUOUS_ANALYSIS_SOURCES".equals(invalid.code()))throw invalid;
                    return Understanding.clarify(invalid.getMessage());
                }
            }
        };
    }
    @Bean @Profile("!business") IdentityPort demoIdentity() {
        return credential -> switch (credential == null ? "" : credential) {
            case "demo-alice" -> new AccessScope("demo", "alice", Set.of(1001L));
            case "demo-bob" -> new AccessScope("demo", "bob", Set.of(1002L));
            default -> throw new AgentException("UNAUTHORIZED", "Use Bearer demo-alice or demo-bob in local demo mode");
        };
    }
    @Bean @Profile("!business & !persistent") BusinessQueryPort demoQuery() {
        return (query, scope) -> {
            if (!scope.tenantId().equals("demo") || !scope.videoIds().contains(query.videoId()))
                throw new AgentException("FORBIDDEN", "Video is outside your data scope");
            if (!query.metric().equals("bullet_count"))
                throw new AgentException("METRIC_UNSUPPORTED", "Unsupported metric");
            return new MetricData(query.videoId(), query.date(), "bullet_count", 128L,
                    "bullet-count-demo-v1", "synthetic-fixture", Instant.now(), "Asia/Shanghai", true, true);
        };
    }
    @Bean @Profile("!deepseek") ModelPort scriptedDemoModel() {
        return context -> {
            if(context.task()!=null && context.task().specification().typed() instanceof com.tianmu.agent.core.TaskConditions.Metric metric)
                return metricDecision(context,metric.videoId(),metric.date());
            if(context.task()!=null && "danmaku.overview".equals(context.task().specification().type())) {
                if(!context.activeSkills().contains("danmaku-analysis"))return Decision.tool("read_skill",Map.of("name","danmaku-analysis"));
                var found=context.observations().stream().filter(r->r.tool().equals("query_danmaku")).findFirst();
                if(found.isEmpty())return Decision.tool("query_danmaku",context.task().specification().conditions());
                var d=(com.tianmu.agent.core.DanmakuTypes.Dataset)found.get().data().get("analysis");
                return Decision.answer("【模拟数据】范围 "+d.query().start()+" 至 "+d.query().end()+"；弹幕 "+d.totalMessages()+" 条，参与用户 "+d.uniqueSenders()+" 人。",List.of(found.get().evidenceId()));
            }
            if(context.task()!=null && context.task().specification().reading()!=null && com.tianmu.agent.application.AnalysisExecutionPolicy.permits("analyze_feedback",context.task().specification())) {
                String skill="comments.analysis".equals(context.task().specification().type())?"comment-analysis":context.task().specification().type().equals("audience.report")?"audience-report":"danmaku-analysis";
                if(!context.activeSkills().contains(skill))return Decision.tool("read_skill",Map.of("name",skill));
                if(context.task().specification().reading().reportRequired()){
                    var contract=context.task().specification().reading();
                    var visual=context.observations().stream().filter(r->r.tool().equals("analyze_video_content")).findFirst();
                    if(contract.includes(com.tianmu.agent.core.AnalysisContract.Source.VIDEO) && visual.isEmpty()){
                        var request=com.tianmu.agent.application.FeedbackTaskPolicy.request(context.task().specification());
                        return Decision.tool("analyze_video_content",Map.of("video_id",request.query().videoId()));
                    }
                    var calls=new ArrayList<ToolCall>();var arguments=new HashMap<String,Object>();
                    for(var source:contract.textSources()){
                        String tool=source.name().equals("COMMENT")?"analyze_comments":"analyze_danmaku";
                        var ready=context.observations().stream().filter(r->r.tool().equals(tool)).findFirst();
                        if(ready.isEmpty())calls.add(new ToolCall(tool,tool.equals("analyze_danmaku") && visual.isPresent()?Map.of("video_evidence_id",visual.get().evidenceId()):Map.of()));
                        else arguments.put(source.name().equals("COMMENT")?"comment_evidence_id":"danmaku_evidence_id",ready.get().evidenceId());
                    }
                    if(!calls.isEmpty())return new Decision(Kind.TOOL,calls.get(0),null,null,calls.subList(1,calls.size()));
                    return Decision.tool("create_feedback_report",arguments);
                }
                var analyzed=context.observations().stream().filter(r->r.tool().equals("analyze_feedback")).findFirst();
                if(analyzed.isEmpty())return Decision.tool("analyze_feedback",Map.of());
                if(context.task().specification().reading().reportRequired())return Decision.tool("create_feedback_report",Map.of("evidence_id",analyzed.get().evidenceId()));
                if(context.observations().stream().noneMatch(r->r.tool().equals("read_result")))return Decision.tool("read_result",Map.of("result_id",analyzed.get().evidenceId(),"section","topics"));
                return Decision.answer("【模拟数据】后台已完成所选来源的反馈分析，这是确定性演示结果。",List.of(analyzed.get().evidenceId()));
            }
            var id = DanmakuTarget.matcher(context.message());
            if (!id.find() || !context.message().contains("弹幕"))
                return Decision.clarify("请提供单个videoId和日期，例如：查询视频1001昨天的弹幕数；直播间1001也使用相同标识。");
            long videoId;
            try { videoId = Long.parseLong(id.group(1)); }
            catch (NumberFormatException e) { return Decision.clarify("请提供有效的视频ID。"); }
            LocalDate date;
            var explicitDate = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(context.message());
            try {
                if (explicitDate.find()) date = LocalDate.parse(explicitDate.group());
                else if (context.message().contains("昨天")) date = context.businessDate().minusDays(1);
                else return Decision.clarify("请指定一个 YYYY-MM-DD 格式的日期，或使用“昨天”。");
            } catch (DateTimeException e) { return Decision.clarify("日期无效，请使用 YYYY-MM-DD。"); }
            if (!context.activeSkills().contains("video-metrics"))
                return Decision.tool("read_skill", Map.of("name", "video-metrics"));
            Optional<ToolResult> metric = context.observations().stream()
                    .filter(r -> r.tool().equals("query_video_metrics")).findFirst();
            if (metric.isEmpty()) return Decision.tool("query_video_metrics", Map.of(
                    "video_id", videoId, "date", date.toString(), "metric", "bullet_count"));
            ToolResult result = metric.get();
            return Decision.answer("【模拟数据】视频 " + videoId + " 在 " + date
                    + " 的弹幕数为 " + result.data().get("value") + " 条。此结果仅用于验证基架，不代表真实业务数据。",
                    List.of(result.evidenceId()));
        };
    }
    private static Decision metricDecision(ModelContext context,long videoId,LocalDate date) {
        if(!context.activeSkills().contains("video-metrics"))return Decision.tool("read_skill",Map.of("name","video-metrics"));
        var metric=context.observations().stream().filter(r->r.tool().equals("query_video_metrics")).findFirst();
        if(metric.isEmpty())return Decision.tool("query_video_metrics",Map.of("video_id",videoId,"date",date.toString(),"metric","bullet_count"));
        var result=metric.get();
        return Decision.answer("【模拟数据】视频 "+videoId+" 在 "+date+" 的弹幕数为 "+result.data().get("value")+" 条。此结果仅用于验证基架，不代表真实业务数据。",List.of(result.evidenceId()));
    }
    @Bean @Profile("!business") com.tianmu.agent.query.DanmakuQueryPort demoDanmakuQuery() {
        return (query, scope, control) -> {
            control.checkpoint();
            if (!scope.videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "videoId is outside demo scope");
            var buckets = new ArrayList<com.tianmu.agent.core.DanmakuTypes.Bucket>();
            var samples = new ArrayList<com.tianmu.agent.core.DanmakuTypes.Sample>();
            String[] texts = {"这款什么时候发货？", "优惠券在哪里领取？", "主播讲得很清楚，材质也展示到了。", "刚进直播间，商品链接在哪？",
                    "有黑色和大码吗？", "声音小了些，后排听不清。", "收到后不合适，怎么申请退换？", "价格比我上次买的实惠。"};
            long[] counts = {4, 5, 7, 10, 16, 22, 18, 13, 9, 7, 5, 4};
            long seconds = Duration.between(query.start(), query.end()).getSeconds(), width = Math.max(1, (seconds + 11) / 12), total = 0;
            for (int i = 0; i < 12; i++) {
                if (width * i >= seconds) { buckets.add(new com.tianmu.agent.core.DanmakuTypes.Bucket(i, 0)); continue; }
                buckets.add(new com.tianmu.agent.core.DanmakuTypes.Bucket(i, counts[i]));
                total += counts[i];
                int[] categories = {0, 1, 2 + i % 6, 2 + (i + 3) % 6};
                for (int j = 0; j < categories.length; j++) {
                    String text = switch (categories[j]) {
                        case 0 -> texts[0];
                        case 1 -> texts[1];
                        case 2 -> texts[2];
                        case 3 -> texts[3];
                        case 4 -> texts[4];
                        case 5 -> texts[5];
                        case 6 -> texts[6];
                        default -> texts[7];
                    };
                    samples.add(new com.tianmu.agent.core.DanmakuTypes.Sample(Integer.toString(100000 + i * 4 + j), i, text, false));
                }
            }
            return new com.tianmu.agent.core.DanmakuTypes.Dataset(query, total, Math.min(total,35), buckets, samples, Instant.now(), "synthetic-danmaku-fixture", true);
        };
    }
}
