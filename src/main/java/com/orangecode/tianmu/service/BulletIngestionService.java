package com.orangecode.tianmu.service;

import com.orangecode.tianmu.application.DeletedBullets;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Bounded atomic batches; video locks coordinate ingestion, moderation and duplicate delivery. */
@Service
public class BulletIngestionService {
    public static final int MAX_BATCH_SIZE = 50;
    private final JdbcTemplate db;
    private final ZoneId datetimeZone;
    private volatile String timestampType;
    public BulletIngestionService(JdbcTemplate db) { this(db,"Asia/Shanghai"); }
    @Autowired public BulletIngestionService(JdbcTemplate db,
            @Value("${agent.bridge.datetime-storage-zone:Asia/Shanghai}") String zone) {
        this.db=db; this.datetimeZone=ZoneId.of(zone);
    }
    @Transactional(rollbackFor=Exception.class)
    public void persist(SendBulletRequest event) { persistBatch(Collections.singletonList(event)); }

    @Transactional(rollbackFor=Exception.class)
    public void persistBatch(List<SendBulletRequest> events) {
        long start=System.nanoTime();
        try { writeBatch(events); }
        finally { com.orangecode.tianmu.common.DanmakuMetrics.observe(com.orangecode.tianmu.common.DanmakuMetrics.Stage.DB_BATCH_BODY,System.nanoTime()-start); }
    }
    private void writeBatch(List<SendBulletRequest> events) {
        if(events==null || events.isEmpty() || events.size()>MAX_BATCH_SIZE)
            throw new IllegalArgumentException("Invalid bullet batch size");
        var unique=new TreeMap<Long,SendBulletRequest>();
        for(var event:events) {
            validate(event);
            var previous=unique.putIfAbsent(event.getBulletId(),event);
            if(previous!=null && !fingerprint(previous).equals(fingerprint(event))) throw conflict();
        }
        // Acquire all video locks in the same order; deletion takes the same exclusive lock.
        var videos=new TreeSet<Long>(); unique.values().forEach(e->videos.add(e.getVideoId()));
        var existingVideos=new HashSet<Long>();
        for(long video:videos) {
            if(!db.queryForList("SELECT video_id FROM video WHERE video_id=? AND is_delete=0 FOR UPDATE",video).isEmpty()) existingVideos.add(video);
        }
        var ids=new ArrayList<>(unique.keySet());
        String in=String.join(",",Collections.nCopies(ids.size(),"?"));
        var deleted=new HashMap<Long,String>();
        db.query("SELECT bullet_id,payload_hash FROM deleted_bullet WHERE bullet_id IN ("+in+")",
                (org.springframework.jdbc.core.RowCallbackHandler) r->deleted.put(r.getLong("bullet_id"),r.getString("payload_hash")),ids.toArray());
        var previous=new HashMap<Long,Map<String,Object>>();
        for(var row:db.queryForList("SELECT bullet_id,video_id,user_id,content,playback_time,color FROM bullet WHERE bullet_id IN ("+in+")",ids.toArray()))
            previous.put(((Number)row.get("bullet_id")).longValue(),row);
        var userIds=unique.values().stream().map(SendBulletRequest::getUserId).distinct().sorted().toList();
        var validUsers=new HashSet<>(db.queryForList("SELECT user_id FROM `user` WHERE is_delete=0 AND user_id IN ("+
                String.join(",",Collections.nCopies(userIds.size(),"?"))+")",Long.class,userIds.toArray()));
        var pending=new ArrayList<SendBulletRequest>();
        var counts=new TreeMap<Long,Integer>();
        for(var event:unique.values()) {
            String tombstone=deleted.get(event.getBulletId());
            if(tombstone!=null) { if(!tombstone.equals(fingerprint(event))) throw conflict(); continue; }
            if(!existingVideos.contains(event.getVideoId())) throw new IllegalArgumentException("Bullet video unavailable");
            if(!validUsers.contains(event.getUserId()))
                throw new IllegalArgumentException("Bullet user unavailable");
            var saved=previous.get(event.getBulletId());
            if(saved!=null) {
                if(((Number)saved.get("video_id")).longValue()!=event.getVideoId()
                    || ((Number)saved.get("user_id")).longValue()!=event.getUserId()
                    || !Objects.equals(saved.get("content"),event.getContent())
                    || !Objects.equals(saved.get("color"),event.getColor())
                    || Double.compare(((Number)saved.get("playback_time")).doubleValue(),event.getPlaybackTime())!=0) throw conflict();
                continue;
            }
            pending.add(event); counts.merge(event.getVideoId(),1,Integer::sum);
        }
        if(pending.isEmpty()) return;
        String type=timestampType();
        String sql="INSERT INTO bullet(bullet_id,video_id,user_id,content,color,playback_time,create_time) VALUES(?,?,?,?,?,?,"+(type.equals("timestamp")?"FROM_UNIXTIME(?)":"?")+")";
        var args=new ArrayList<Object[]>();
        for(var event:pending) {
            Date created=event.getCreateTime();
            if(created==null) { created=new Date(); org.slf4j.LoggerFactory.getLogger(getClass()).warn("legacy_bullet_timestamp_missing bullet_id={}",event.getBulletId()); }
            Object timestamp=type.equals("timestamp")?java.math.BigDecimal.valueOf(created.getTime(),3)
                :LocalDateTime.ofInstant(created.toInstant(),datetimeZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            args.add(new Object[]{event.getBulletId(),event.getVideoId(),event.getUserId(),event.getContent(),event.getColor(),event.getPlaybackTime(),timestamp});
        }
        db.batchUpdate(sql,args);
        for(var count:counts.entrySet()) {
            db.update("INSERT INTO video_stats(video_id,view_count,bullet_count,like_count,coin_count,favorite_count,comment_count,create_time,update_time,is_delete) VALUES(?,0,0,0,0,0,0,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0) ON DUPLICATE KEY UPDATE video_id=video_id",count.getKey());
            if(db.update("UPDATE video_stats SET bullet_count=COALESCE(bullet_count,0)+?,bullet_revision=bullet_revision+1,update_time=UTC_TIMESTAMP() WHERE video_id=?",count.getValue(),count.getKey())!=1)
                throw new IllegalStateException("Bullet counter update failed");
        }
    }
    private String timestampType() {
        String value=timestampType;
        if(value==null) synchronized(this) {
            value=timestampType;
            if(value==null) {
                value=db.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bullet' AND COLUMN_NAME='create_time'",String.class);
                if(!Set.of("timestamp","datetime").contains(value)) throw new IllegalStateException("Unsupported bullet timestamp column");
                timestampType=value;
            }
        }
        return value;
    }
    private static String fingerprint(SendBulletRequest e) { return DeletedBullets.fingerprint(e.getVideoId(),e.getUserId(),e.getContent(),e.getColor(),e.getPlaybackTime()); }
    private static IllegalArgumentException conflict() { return new IllegalArgumentException("Bullet event ID conflicts with existing payload"); }
    private static void validate(SendBulletRequest e) {
        if(e==null || e.getBulletId()==null || e.getBulletId()<=0 || e.getVideoId()==null || e.getUserId()==null || e.getContent()==null || e.getContent().isBlank()
            || e.getPlaybackTime()==null || !Double.isFinite(e.getPlaybackTime()) || e.getPlaybackTime()<0) throw new IllegalArgumentException("Invalid bullet event");
    }
}
