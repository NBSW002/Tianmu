package com.tianmu.agent.harness;

import com.tianmu.agent.application.AnalysisExecutionPolicy;
import com.tianmu.agent.core.*;
import com.tianmu.agent.preparation.TaskMeaning;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.ToolCatalog;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackSinglePathTest {
    @Test void noRetiredToolsOrMigrationFlagCanSelectAnAlternativeWorkflow() {
        var tools=new ToolCatalog();var skills=new SkillCatalog();
        var retired=Set.of("query_audience","query_video_comments","create_danmaku_report");
        assertTrue(Collections.disjoint(tools.all().keySet(),retired));
        for(String name:skills.names())assertTrue(Collections.disjoint(skills.read(name).tools(),retired),name);
        assertTrue(Arrays.stream(com.tianmu.agent.feedback.FeedbackPolicy.class.getRecordComponents()).noneMatch(c->c.getName().equals("enabled")));
    }
    @Test void everyTextAnalysisUsesBackgroundEvidenceAndCannotDeliverRawLegacyEvidence() {
        for(String question:List.of("分析视频1001的评论，不需要报告","分析视频1001的弹幕报告，不看视频","分析视频1001的评论和弹幕报告，不看视频")) {
            var spec=TaskMeaning.defaults().resolve(question,LocalDate.now());
            assertTrue(AnalysisExecutionPolicy.permits("analyze_feedback",spec));
            assertFalse(AnalysisExecutionPolicy.permits("query_danmaku",spec));
            assertFalse(AnalysisExecutionPolicy.permits("create_video_report",spec));
            assertEquals("INVALID_EVIDENCE",assertThrows(AgentException.class,()->AnalysisExecutionPolicy.requireComplete(spec,List.of())).code());
        }
    }
    @Test void countOverviewAndVideoOnlyReportRemainIndependentCapabilities() {
        var overview=TaskMeaning.defaults().resolve("视频1001现在一共有多少条弹幕",LocalDate.now());
        assertTrue(AnalysisExecutionPolicy.permits("query_danmaku",overview));
        assertFalse(AnalysisExecutionPolicy.permits("analyze_feedback",overview));
        var video=TaskMeaning.defaults().resolve("生成视频1001的视频内容报告，只看视频内容",LocalDate.now());
        assertTrue(AnalysisExecutionPolicy.permits("create_video_report",video));
        assertFalse(AnalysisExecutionPolicy.permits("analyze_feedback",video));
    }
    @Test void historicalCheckpointsRemainReadableWithoutAnExecutor() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        String old="{\"kind\":\"audience-report-checkpoint-v1\",\"evidenceId\":\"ev_old\",\"classification\":{\"offset\":40,\"topics\":[],\"videoEvidenceId\":null,\"merged\":false}}";
        var restored=json.readValue(old,ReportCheckpoint.class);assertEquals(40,restored.classification().offset());
        assertEquals(restored,json.readValue(json.writeValueAsBytes(restored),ReportCheckpoint.class));
    }
}
