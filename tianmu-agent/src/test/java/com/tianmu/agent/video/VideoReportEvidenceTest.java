package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.tool.ToolContext;
import com.tianmu.agent.tool.builtin.CreateVideoReportTool;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoReportEvidenceTest {
    @Test void videoOnlyReportEscapesUntrustedContentAndRequiresTheExactSourceContract() {
        var context=mock(ToolContext.class);var owner=new AccessScope("t","u",Set.of(7L));
        var facts=new VideoContent.Facts("video-content-v1",7,"a".repeat(64),0,1000,"<script>bad</script>",List.of(new VideoContent.Moment(500,"画面")),"<audio>","SAMPLED_FRAMES_AND_AUDIO_TRANSCRIPT",List.of(500L),"vision",Instant.now());
        when(context.evidence("video")).thenReturn(new Evidence("video",UUID.randomUUID(),owner,null,null,facts));
        when(context.analysisContract()).thenReturn(new AnalysisContract(Set.of(AnalysisContract.Source.VIDEO),true));
        when(context.saveReport(eq("video"),any())).thenReturn(new ReportRef("report","/report"));
        var result=new CreateVideoReportTool().execute(new ToolCall("create_video_report",Map.of("evidence_id","video")),context);
        var html=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(context).saveReport(eq("video"),html.capture());
        String document=new String(html.getValue(),StandardCharsets.UTF_8);
        assertTrue(document.contains("00:00.500"));assertTrue(document.contains("&lt;script&gt;"));assertFalse(document.contains("<script>bad"));
        assertTrue(document.contains("&lt;audio&gt;"));assertEquals(List.of("video"),result.terminalAnswer().evidenceIds());
        when(context.analysisContract()).thenReturn(new AnalysisContract(Set.of(AnalysisContract.Source.VIDEO,AnalysisContract.Source.COMMENT),true));
        assertEquals("TASK_CONSTRAINT_VIOLATION",assertThrows(AgentException.class,()->new CreateVideoReportTool().execute(new ToolCall("create_video_report",Map.of("evidence_id","video")),context)).code());
    }
}
