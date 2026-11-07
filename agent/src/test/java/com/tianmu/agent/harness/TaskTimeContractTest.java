package com.tianmu.agent.harness;

import com.tianmu.agent.application.ClarificationInputs;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TaskTimeContractTest {
    final LocalDate today=LocalDate.now(DanmakuTypes.ZONE), day=today.minusDays(1);
    String chinese(LocalDate date){return date.getYear()+"年"+date.getMonthValue()+"月"+date.getDayOfMonth()+"日";}
    Input input(String text){return new Input(text,today,new AccessScope("demo","alice",Set.of(1001L)));}

    @Test void chineseAndIsoSpellingFreezeTheSameMetricAndReportConditionsWithoutRewritingTheUserText() {
        var meaning=TaskMeaning.defaults();String original="查询视频1001在"+chinese(day)+"的弹幕数";
        var iso=meaning.resolve(original.replace(chinese(day),day.toString()),today);
        var cn=meaning.resolve(original,today);
        assertEquals(iso,cn);assertEquals("video.metric",cn.type());assertEquals(day.toString(),cn.conditions().get("date"));
        assertEquals("user_input",cn.provenance().get("date"));assertTrue(new MetricQueryReadiness().validate(input(original)).isEmpty());
        assertEquals(meaning.resolve("只根据视频1001在"+day+"的评论生成报告",today),
                meaning.resolve("只根据视频1001在"+chinese(day)+"的评论生成报告",today));
        assertEquals(day.atStartOfDay(),DanmakuReportTask.parse("分析视频1001在"+chinese(day)+"的弹幕",today).start());
    }
    @ParameterizedTest @ValueSource(strings={"2026年2月30日","2026年13月1日","2026/10/26","2026.10.26","2026-10","10月26日","二〇二六年十月二十六日","上个月","去年","周一","最近三天"})
    void invalidOrUnsupportedExplicitDatesNeverBecomeAllTime(String date) {
        String text="查询视频1001在"+date+"的弹幕数";
        assertTrue(new MetricTaskSpecificationResolver().resolve(text,today).isEmpty());
        assertTrue(new MetricQueryReadiness().validate(input(text)).orElseThrow().required());
        assertThrows(RuntimeException.class,()->DanmakuReportTask.parse("分析视频1001在"+date+"的弹幕并生成报告",today));
    }
    @ParameterizedTest @ValueSource(strings={"mixed","two-days","bare-clock","part-day","mixed-recent","open-range"})
    void conflictingDatesAndIncompleteClockRangesRequireClarification(String mode) {
        String time=switch(mode){case "mixed"->chinese(day)+"和昨天";case "two-days"->chinese(day)+"到"+today;case "bare-clock"->chinese(day)+" 12点到13点";case "mixed-recent"->chinese(day)+"和最近七天";case "open-range"->chinese(day)+"之后";default->chinese(day)+"晚上";};
        assertTrue(new MetricQueryReadiness().validate(input("查询视频1001在"+time+"的弹幕数")).orElseThrow().required());
    }
    @Test void validFullRangesAndExistingDefaultsRemainDistinct() {
        var request=ZonedDateTime.now(DanmakuTypes.ZONE);
        var range=DanmakuReportTask.parse("分析视频1001从"+chinese(day)+" 12:00到"+chinese(day)+" 13:00的弹幕",today,request);
        assertEquals(day.atTime(12,0),range.start());assertEquals(day.atTime(13,0),range.end());
        assertEquals(DanmakuTypes.ALL_TIME_START,DanmakuReportTask.parse("分析视频1001全部弹幕",today,request).start());
        assertEquals(request.toLocalDateTime().withNano(0).minusDays(7),DanmakuReportTask.parse("分析视频1001最近七天的弹幕",today,request).start());
    }
    @Test void clarificationDatesAreNormalizedAndUnsupportedRepliesCannotEraseTheOriginalDate() {
        String reply=chinese(day);
        assertEquals("查询视频1001 "+day+"的弹幕数",ClarificationInputs.combine("查询视频1001的弹幕数",reply,today));
        assertEquals(day.toString(),ClarificationInputs.confirmed("查询视频1001在"+reply+"的弹幕数",today).get("date"));
        assertEquals("INVALID_REQUEST",assertThrows(AgentException.class,()->ClarificationInputs.combine("查询视频1001昨天的弹幕数","10月26日",today)).code());
        assertTrue(ClarificationInputs.combine("查询视频1001在2026年2月30日的弹幕数",reply,today).contains(day.toString()));
    }
    @ParameterizedTest @ValueSource(strings={"2026年10月26日 12点到13点","2026年10月26日和昨天","2026年10月26日之后","从2026年10月26日","2026-10-26 12:00","12点到13点"})
    void clarificationNeverDropsTimeQualifiersFromReplyOrOriginal(String time) {
        assertEquals("INVALID_REQUEST",assertThrows(AgentException.class,()->ClarificationInputs.combine("查询视频1001昨天的弹幕数",time,today)).code());
        assertEquals("INVALID_REQUEST",assertThrows(AgentException.class,()->ClarificationInputs.combine("查询视频1001在"+time+"的弹幕数","视频1001",today)).code());
        String missingVideo=ClarificationInputs.combine("查询昨天的弹幕数",time,today);
        assertTrue(new MetricQueryReadiness().validate(input(missingVideo)).orElseThrow().required());
    }
    @Test void clarificationKeepsSupportedRangesInEitherOriginalOrReply() {
        String originalRange=ClarificationInputs.combine("查询最近七天的弹幕数","视频1001",today);
        String replyRange=ClarificationInputs.combine("查询视频1001昨天的弹幕数","最近七天",today);
        assertTrue(originalRange.contains("最近七天"));assertTrue(replyRange.contains("最近七天"));assertFalse(replyRange.contains("昨天"));
        var request=today.atStartOfDay(DanmakuTypes.ZONE);
        for(String text:List.of(originalRange,replyRange)) {
            var query=DanmakuReportTask.parse(text,today,request);
            assertEquals(request.toLocalDateTime().minusDays(7),query.start());
        }
        assertFalse(ClarificationInputs.combine("查询视频1001昨天的弹幕数","全部弹幕",today).contains("昨天"));
    }
    @Test void actualPreparationAndToolExecutionKeepTheChineseDayInsteadOfAnOverview() {
        var helper=new PreparationFlowTest();
        try(var f=new HarnessLifecycleTest.Fixture();var prep=new PreparationService(helper.miss(),new PreparationFlowTest.Model(){
            public Intent intent(Input i,com.tianmu.agent.runtime.control.CallControl c){throw new AssertionError("No preparation model needed");}
            public Understanding understand(Input i,com.tianmu.agent.runtime.control.CallControl c){throw new AssertionError("No preparation model needed");}
        },new PreparationPolicy(Duration.ofMillis(200),Duration.ofMillis(400),Duration.ofMillis(200),Duration.ofMillis(400),true,false),f.harness,List.of(new MetricQueryReadiness()))) {
            String message="查询视频1001在"+chinese(day)+"的弹幕数";var run=new RunState(new AccessScope("demo","alice",Set.of(1001L)),message);
            var count=new java.util.concurrent.atomic.AtomicInteger();
            helper.execute(f,prep,run,context->{
                assertEquals(message,context.message());assertEquals("video.metric",context.task().specification().type());
                assertEquals(day.toString(),context.task().specification().conditions().get("date"));
                return switch(count.getAndIncrement()) {
                    case 0->Decision.tool("read_skill",Map.of("name","video-metrics"));
                    case 1->Decision.tool("query_video_metrics",Map.of("video_id",1001,"date",day.toString(),"metric","bullet_count"));
                    default->Decision.answer("128",List.of(context.observations().get(1).evidenceId()));
                };
            });
            assertEquals(Status.SUCCEEDED,run.snapshot().status());assertEquals(1,f.queries.get());assertEquals(0,run.snapshot().preparationCalls());
            assertTrue(run.execution().exchanges().stream().noneMatch(e->e.call().name().equals("query_danmaku")));
        }
    }
}
