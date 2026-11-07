package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;

/** Cache keys include tenant, actor, source version, range, generic extraction strategy and model configuration. Authorization precedes every read. */
@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(VideoCachePolicy.class)
public final class VideoContentCache {

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private volatile long retryAfter;
    private final VideoCachePolicy policy;
    private final com.github.benmanes.caffeine.cache.Cache<String,VideoContent.Facts> memory;
    public VideoContentCache(ObjectProvider<DataSource> source,ObjectMapper json){this(source,json,VideoCachePolicy.defaults());}
    public VideoContentCache(ObjectProvider<DataSource> source,ObjectMapper json,VideoCachePolicy policy){this(source,json,policy,VideoRuntimeProperties.defaults());}
    @org.springframework.beans.factory.annotation.Autowired
    public VideoContentCache(ObjectProvider<DataSource> source,ObjectMapper json,VideoCachePolicy policy,VideoRuntimeProperties governance){this(source.getIfAvailable()==null?null:new JdbcTemplate(source.getIfAvailable()),json,policy,governance);}
    VideoContentCache(JdbcTemplate db,ObjectMapper json){this(db,json,VideoCachePolicy.defaults());}
    VideoContentCache(JdbcTemplate db,ObjectMapper json,VideoCachePolicy policy){this(db,json,policy,VideoRuntimeProperties.defaults());}
    private final VideoRuntimeProperties governance;
    VideoContentCache(JdbcTemplate db,ObjectMapper json,VideoCachePolicy policy,VideoRuntimeProperties governance){this.governance=governance;
        this.db=db;this.json=json;this.policy=policy;if(db!=null)db.setQueryTimeout(governance.queryTimeoutSeconds());
        var ttl=policy.retention().isZero() || policy.memoryTtl().compareTo(policy.retention())<=0?policy.memoryTtl():policy.retention();
        memory=com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(Math.min(governance.localCacheCap(),policy.maxEntries())).expireAfterWrite(ttl).build();
    }
    public VideoContent.Facts get(String key) {
        if (db == null || !available()) return retained(memory.getIfPresent(key));
        List<String> rows;
        try{rows=db.queryForList("SELECT facts_json FROM agent_video_content_cache WHERE cache_key=? AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP(6))", String.class, key);}
        catch(org.springframework.dao.DataAccessException e){unavailable();return retained(memory.getIfPresent(key));}
        if (rows.isEmpty()) return null;
        try { return retained(json.readValue(rows.get(0), VideoContent.Facts.class)); }
        catch (Exception e) { try{db.update("DELETE FROM agent_video_content_cache WHERE cache_key=? AND facts_json=CAST(? AS JSON)",key,rows.get(0));}catch(org.springframework.dao.DataAccessException failure){unavailable();}return null; }
    }
    public void put(String key, VideoContent.Facts facts) {
        memory.put(key,facts);if (db == null || !available())return;
        try {
            String body = json.writeValueAsString(facts);
            db.update("INSERT INTO agent_video_content_cache(cache_key,facts_json,expires_at,updated_at) VALUES(?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE facts_json=VALUES(facts_json),expires_at=VALUES(expires_at),updated_at=VALUES(updated_at)",key,body,policy.retention().isZero()?null:java.sql.Timestamp.from(facts.analyzedAt().plus(policy.retention())));
            db.update("DELETE FROM agent_video_content_cache WHERE expires_at<=UTC_TIMESTAMP(6) LIMIT ?",governance.cacheCleanupBatch());
            Long count=db.queryForObject("SELECT COUNT(*) FROM agent_video_content_cache",Long.class);
            if(count!=null && count>policy.maxEntries())db.update("DELETE FROM agent_video_content_cache ORDER BY updated_at LIMIT ?",Math.min(count-policy.maxEntries(),128));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Invalid video cache payload"); }
        catch(org.springframework.dao.DataAccessException e){unavailable();}
    }
    public void invalidate(String key){memory.invalidate(key);if(db!=null)try{db.update("DELETE FROM agent_video_content_cache WHERE cache_key=?",key);}catch(org.springframework.dao.DataAccessException unavailable){unavailable();}}
    private VideoContent.Facts retained(VideoContent.Facts facts){return facts!=null && !policy.retention().isZero() && !Instant.now().isBefore(facts.analyzedAt().plus(policy.retention()))?null:facts;}
    private boolean available(){return retryAfter==0 || System.nanoTime()-retryAfter>=0;}
    private void unavailable(){retryAfter=System.nanoTime()+java.time.Duration.ofSeconds(governance.storageRetrySeconds()).toNanos();org.slf4j.LoggerFactory.getLogger(getClass()).warn("video_cache_storage_unavailable");}
}
