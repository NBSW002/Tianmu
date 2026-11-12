package com.orangecode.tianmu.application;

import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class WatchHistory {
    private final JdbcTemplate db;
    public WatchHistory(JdbcTemplate db) { this.db=db; }
    public record Item(String videoId,String title,String coverUrl,double positionSeconds,String watchedAt,double duration,String creatorName) {}
    public CreatorWorkspace.Page<Item> list(long owner,int page) {
        if(page<1 || page>10000) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        var rows=db.query("SELECT h.video_id,v.title,v.cover_url,h.position_seconds,h.watched_at,v.duration,u.nickname FROM user_video_history h JOIN video v ON v.video_id=h.video_id LEFT JOIN `user` u ON u.user_id=v.user_id WHERE h.user_id=? AND v.status=2 AND v.is_delete=0 ORDER BY h.watched_at DESC,h.video_id DESC LIMIT 21 OFFSET ?",
            (r,n)->new Item(r.getString("video_id"),r.getString("title"),r.getString("cover_url"),r.getDouble("position_seconds"),r.getObject("watched_at",java.time.LocalDateTime.class).atOffset(java.time.ZoneOffset.UTC).toString(),r.getDouble("duration"),r.getString("nickname")),owner,(page-1)*20);
        return new CreatorWorkspace.Page<>(List.copyOf(rows.subList(0,Math.min(20,rows.size()))),rows.size()>20);
    }
    @Transactional
    public void record(long owner,long video,double seconds) {
        if(!Double.isFinite(seconds) || seconds<0) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        var rows=db.queryForList("SELECT duration FROM video WHERE video_id=? AND status=2 AND is_delete=0 FOR UPDATE",video);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        double duration=((Number)rows.get(0).get("duration")).doubleValue();
        if(seconds>duration+1) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        double position=Math.min(seconds,duration);
        var now=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        db.update("INSERT INTO user_video_history(user_id,video_id,position_seconds,watched_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE position_seconds=?,watched_at=?",owner,video,position,now,position,now);
    }
    public void delete(long owner,long video) { db.update("DELETE FROM user_video_history WHERE user_id=? AND video_id=?",owner,video); }
    public void clear(long owner) { db.update("DELETE FROM user_video_history WHERE user_id=?",owner); }
}
