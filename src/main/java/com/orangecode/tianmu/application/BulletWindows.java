package com.orangecode.tianmu.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;

/** Complete, bounded pages from a fixed sixty-second playback window. */
@Service
public class BulletWindows {
    private final JdbcTemplate db; private final StringRedisTemplate redis; private final ObjectMapper json; private final VideoLookup videos;
    public BulletWindows(JdbcTemplate db,StringRedisTemplate redis,ObjectMapper json,VideoLookup videos) { this.db=db;this.redis=redis;this.json=json;this.videos=videos; }
    public record Page(List<OnlineBulletResponse> items,boolean hasMore,double nextTime,String nextId,long version) {}
    public Page read(long video,int bucket,double afterTime,long afterId) {
        double start=bucket*60d,end=start+60;
        if(bucket<0 || bucket>100000 || !Double.isFinite(afterTime) || afterTime<start || afterTime>=end || afterId<0) throw new com.orangecode.tianmu.exception.BusinessException(com.orangecode.tianmu.common.ErrorCode.PARAMS_ERROR);
        videos.requirePublished(video);
        long version=revision(video); String key="video:"+video+":bullets:window:"+bucket;
        boolean first=afterTime==start && afterId==0;
        if(first) try { String raw=redis.opsForValue().get(key); if(raw!=null){var cached=json.readValue(raw,Page.class);if(cached.version()==version)return cached;} } catch(Exception unavailable) { /* DB remains authoritative. */ }
        var rows=db.query("SELECT bullet_id,user_id,content,playback_time FROM bullet WHERE video_id=? AND playback_time>=? AND playback_time<? AND (playback_time>? OR (playback_time=? AND bullet_id>?)) ORDER BY playback_time,bullet_id LIMIT 501",(r,n)->{
            var item=new OnlineBulletResponse();item.setBulletId(r.getString("bullet_id"));item.setUserId(r.getString("user_id"));item.setText(r.getString("content"));item.setPlaybackTime(r.getDouble("playback_time"));return item;
        },video,start,end,afterTime,afterTime,afterId);
        boolean more=rows.size()>500; var items=List.copyOf(rows.subList(0,Math.min(500,rows.size())));
        var last=items.isEmpty()?null:items.get(items.size()-1);
        var page=new Page(items,more,last==null?afterTime:last.getPlaybackTime(),last==null?Long.toString(afterId):last.getBulletId(),version);
        // Cache only the first page; pagination cursors cannot create unbounded Redis keys.
        if(first && revision(video)==version) try { redis.opsForValue().set(key,json.writeValueAsString(page),java.time.Duration.ofMinutes(10)); } catch(Exception unavailable) { /* Return DB data. */ }
        return page;
    }
    private long revision(long video) { var rows=db.queryForList("SELECT bullet_revision FROM video_stats WHERE video_id=?",Long.class,video);return rows.isEmpty()?0:rows.get(0); }
}
