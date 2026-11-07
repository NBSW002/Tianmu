package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.preparation.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ReportClarificationContractTest {
    final ZonedDateTime at=ZonedDateTime.of(2026,11,7,15,30,0,0,DanmakuTypes.ZONE);
    @Test void thirtyDaysUsesAcceptedTimeAndKeepsDeclaredSources() {
        var spec=TaskMeaning.defaults().resolve("分析视频1001最近30天的弹幕与评论，生成观众反馈HTML报告，不需要读取视频画面",at.toLocalDate(),at.toInstant());
        assertEquals("audience.report",spec.type());
        assertEquals(Set.of(AnalysisContract.Source.COMMENT,AnalysisContract.Source.DANMAKU),spec.reading().sources());
        assertEquals(at.toLocalDateTime().minusDays(30).toString(),spec.conditions().get("start"));
        assertEquals(at.toLocalDateTime().toString(),spec.conditions().get("end"));
    }
    @Test void reportReplyAcceptsTwoDatesWithoutTurningIntoADailyMetricOrAddingVideoReads() {
        String combined=ClarificationInputs.combine("视频1001的评论和弹幕报告，不读取视频内容","从2026-10-10到2026-10-20",at.toLocalDate(),at.toInstant());
        var spec=TaskMeaning.defaults().resolve(combined,at.toLocalDate(),at.toInstant());
        assertEquals("audience.report",spec.type());
        assertEquals(Set.of(AnalysisContract.Source.COMMENT,AnalysisContract.Source.DANMAKU),spec.reading().sources());
        assertEquals("2026-10-10T00:00",spec.conditions().get("start"));assertEquals("2026-10-20T00:00",spec.conditions().get("end"));
    }
    @Test void aVideoOnlyReplyPreservesTheOriginalRelativeAnchor() {
        String combined=ClarificationInputs.combine("生成最近30天的评论和弹幕报告，不读取视频内容","视频1001",at.toLocalDate(),at.toInstant());
        var range=DanmakuReportTask.parse(combined,at.toLocalDate().plusDays(1),at.plusDays(1));
        assertEquals(at.toLocalDateTime().minusDays(30),range.start());assertEquals(at.toLocalDateTime(),range.end());
    }
    @Test void invalidAndConflictingRangesCannotBecomeAllTimeOrLoseMetricDateConstraints() {
        for(String invalid:new String[]{"最近0天","最近31天","最近7天和最近30天","2026-10-10和2026-10-20","2026-10-20到2026-10-10","2026-10-10到2026-10-20和昨天","2026-10-10到2026-10-20 12点到13点"})
            assertThrows(RuntimeException.class,()->DanmakuReportTask.parse("分析视频1001 "+invalid+" 的评论和弹幕报告",at.toLocalDate(),at),invalid);
        assertThrows(AgentException.class,()->ClarificationInputs.combine("查询视频1001的弹幕数","2026-10-10到2026-10-20",at.toLocalDate()));
        assertThrows(AgentException.class,()->ClarificationInputs.combine("视频1001评论报告","视频1002和视频1003，从2026-10-10到2026-10-20",at.toLocalDate()));
    }
}
