package com.tianmu.agent.preparation;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VideoDiscoveryTest {
    @Test void titleAndLastWeekArePublicationFiltersNotAnArbitraryVideoSelection(){
        var q=VideoDiscovery.request("分析我上周那个小鸭视频的评论",LocalDate.of(2026,11,3)).orElseThrow();
        assertEquals("小鸭",q.query());assertEquals(LocalDate.of(2026,10,26),q.startDate());assertEquals(LocalDate.of(2026,11,2),q.endDate());
        assertTrue(VideoDiscovery.request("分析视频1001昨天的评论",LocalDate.of(2026,11,3)).isEmpty());
        assertTrue(VideoDiscovery.request("视频创作应该怎么做",LocalDate.of(2026,11,3)).isEmpty());
        assertTrue(VideoDiscovery.request("分析这个视频上周的评论",LocalDate.of(2026,11,3)).isEmpty());
        assertEquals("分析上周的评论和视频内容",VideoDiscovery.analysisText("分析上周的评论和视频内容"));
        assertEquals("分析我那个小鸭视频昨天的评论",VideoDiscovery.analysisText("分析我上周那个小鸭视频昨天的评论"));
        assertEquals("分析视频1001上周的评论",VideoDiscovery.analysisText("分析视频1001上周的评论"));
    }
}
