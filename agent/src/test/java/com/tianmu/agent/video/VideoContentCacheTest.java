package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.VideoContent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoContentCacheTest {
    @Test void durableAssetHasNoTimeExpiryByDefaultAndCapacityIsBounded()throws Exception {
        var db=mock(JdbcTemplate.class);when(db.queryForObject(anyString(),eq(Long.class))).thenReturn(3L);
        var mapper=new ObjectMapper().findAndRegisterModules();var cache=new VideoContentCache(db,mapper,new VideoCachePolicy(java.time.Duration.ofHours(1),java.time.Duration.ZERO,2));
        var facts=new VideoContent.Facts("video-content-v1",1001,"version",0,3000,"画面事实",List.of(),"","SAMPLED_FRAMES",List.of(1000L),"vision",Instant.now().minus(java.time.Duration.ofDays(3)));
        cache.put("key",facts);verify(db).update(startsWith("INSERT INTO agent_video_content_cache"),eq("key"),anyString(),isNull());
        verify(db).update("DELETE FROM agent_video_content_cache ORDER BY updated_at LIMIT ?",1L);
        when(db.queryForList(anyString(),eq(String.class),eq("key"))).thenReturn(List.of(mapper.writeValueAsString(facts)));
        assertEquals(facts,cache.get("key"));
        var expiring=new VideoContentCache(db,mapper,new VideoCachePolicy(java.time.Duration.ofHours(1),java.time.Duration.ofDays(1),2));assertNull(expiring.get("key"));
    }
    @Test void slowDatabaseReadDoesNotSerializeOtherKeys() throws Exception {
        var db = mock(JdbcTemplate.class); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(db.queryForList(anyString(), eq(String.class), eq("slow"))).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return List.of();
        });
        when(db.queryForList(anyString(), eq(String.class), eq("fast"))).thenReturn(List.of());
        var cache = new VideoContentCache(db, new ObjectMapper()); var pool = Executors.newFixedThreadPool(2);
        try {
            var slow = pool.submit(() -> cache.get("slow")); assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNull(pool.submit(() -> cache.get("fast")).get(2, TimeUnit.SECONDS));
            assertFalse(slow.isDone()); release.countDown(); assertNull(slow.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void corruptReadOnlyDeletesTheExactPayloadItRead() {
        var db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), eq(String.class), eq("key"))).thenReturn(List.of("{}"));
        assertNull(new VideoContentCache(db, new ObjectMapper()).get("key"));
        verify(db).update("DELETE FROM agent_video_content_cache WHERE cache_key=? AND facts_json=CAST(? AS JSON)", "key", "{}");
    }
    @Test void cachePersistenceFailureDoesNotDiscardFreshFactsOrBreakTheAnalysis(){
        JdbcTemplate db=mock(JdbcTemplate.class);
        when(db.update(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var cache=new VideoContentCache(db,new ObjectMapper().findAndRegisterModules());
        var facts=new VideoContent.Facts("video-content-v1",1001,"version",0,3000,"草地上的小鸭",List.of(),"","SAMPLED_FRAMES",List.of(1000L),"vision",Instant.now());
        assertDoesNotThrow(()->cache.put("key",facts));assertEquals(facts,cache.get("key"));
        verify(db,never()).queryForList(anyString(),eq(String.class),any(Object[].class));
    }
}
