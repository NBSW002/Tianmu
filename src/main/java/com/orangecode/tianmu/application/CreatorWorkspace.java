package com.orangecode.tianmu.application;

import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

@Service
public class CreatorWorkspace {
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    public CreatorWorkspace(JdbcTemplate db, StringRedisTemplate redis) { this.db=db; this.redis=redis; }
    public record Page<T>(List<T> items, boolean hasMore) {}
    public record VideoItem(String videoId,String title,String description,int status,String coverUrl,String updatedAt) {}
    public record FeedbackItem(String itemId,String videoId,String title,String content,String nickname,String createdAt,Double positionSeconds) {}
    public record Edit(String title,String description,String expectedTitle,String expectedDescription) {}
    private static BusinessException missing() { return new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
    private static int offset(int page) {
        if (page<1 || page>10000) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return (page-1)*20;
    }
    private static <T> Page<T> page(List<T> rows) { return new Page<>(List.copyOf(rows.subList(0,Math.min(20,rows.size()))),rows.size()>20); }
    private Map<String,Object> owned(long actor,long video) {
        var rows=db.queryForList("SELECT * FROM video WHERE video_id=? AND user_id=? AND is_delete=0 FOR UPDATE",video,actor);
        if(rows.isEmpty()) throw missing();
        return rows.get(0);
    }
    public Page<VideoItem> videos(long actor,int page) {
        return page(db.query("SELECT video_id,title,description,status,cover_url,update_time FROM video WHERE user_id=? AND is_delete=0 ORDER BY create_time DESC,video_id DESC LIMIT 21 OFFSET ?",
            (r,n)->new VideoItem(r.getString("video_id"),r.getString("title"),r.getString("description"),r.getInt("status"),r.getString("cover_url"),r.getString("update_time")),actor,offset(page)));
    }
    @Transactional
    public void edit(long actor,long video,Edit input) {
        if(input==null || input.title()==null || input.title().isBlank() || input.title().length()>255 || input.description()==null || input.description().length()>4096)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        var old=owned(actor,video);
        if(!Objects.equals(old.get("title"),input.expectedTitle()) || !Objects.equals(Objects.toString(old.get("description"),""),input.expectedDescription()))
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"稿件已更新，请刷新后编辑");
        db.update("UPDATE video SET title=?,description=?,status=CASE WHEN status=2 THEN 1 ELSE status END,update_time=GREATEST(CURRENT_TIMESTAMP(3),TIMESTAMPADD(MICROSECOND,1000,update_time)) WHERE video_id=?",input.title().trim(),input.description(),video);
        invalidate(video);
    }
    @Transactional
    public void status(long actor,long video,int expected,int target) {
        var old=owned(actor,video);
        int current=((Number)old.get("status")).intValue();
        if(current!=expected || !(current==2 && target==0 || current==0 && target==1))
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"稿件状态已变化，请刷新后重试");
        db.update("UPDATE video SET status=?,update_time=GREATEST(CURRENT_TIMESTAMP(3),TIMESTAMPADD(MICROSECOND,1000,update_time)) WHERE video_id=?",target,video);
        invalidate(video);
    }
    @Transactional
    public void deleteVideo(long actor,long video) {
        owned(actor,video);
        db.update("UPDATE video SET is_delete=1,update_time=GREATEST(CURRENT_TIMESTAMP(3),TIMESTAMPADD(MICROSECOND,1000,update_time)) WHERE video_id=?",video);
        if(db.update("UPDATE user_stats SET video_count=video_count-1 WHERE user_id=? AND video_count>0",actor)!=1)
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"投稿统计异常，请联系管理员");
        invalidate(video);
    }
    private static String table(String source) {
        return switch(source) { case "COMMENT" -> "comment"; case "DANMAKU" -> "bullet"; default -> throw new BusinessException(ErrorCode.PARAMS_ERROR); };
    }
    public Page<FeedbackItem> feedback(long actor,String source,int page) {
        String table=table(source), id=source.equals("COMMENT")?"comment_id":"bullet_id";
        String position=source.equals("COMMENT")?"NULL":"f.playback_time";
        return page(db.query("SELECT f."+id+" item_id,f.video_id,v.title,f.content,u.nickname,f.create_time,"+position+" position_seconds FROM "+table+" f JOIN video v ON v.video_id=f.video_id LEFT JOIN `user` u ON u.user_id=f.user_id WHERE v.user_id=? AND v.is_delete=0 ORDER BY f.create_time DESC,f."+id+" DESC LIMIT 21 OFFSET ?",
            (r,n)->new FeedbackItem(r.getString("item_id"),r.getString("video_id"),r.getString("title"),r.getString("content"),r.getString("nickname"),r.getString("create_time"),(Double)r.getObject("position_seconds")),actor,offset(page)));
    }
    @Transactional
    public void deleteFeedback(long actor,String source,long item,Long requestedVideo) {
        String table=table(source), key=source.equals("COMMENT")?"comment_id":"bullet_id";
        var rows=db.queryForList("SELECT video_id FROM "+table+" WHERE "+key+"=?",item);
        if(rows.isEmpty()) throw missing();
        long video=((Number)rows.get(0).get("video_id")).longValue();
        if(requestedVideo!=null && requestedVideo!=video) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        var videos=db.queryForList("SELECT user_id FROM video WHERE video_id=? AND is_delete=0 FOR UPDATE",video);
        if(videos.isEmpty()) throw missing();
        var current=db.queryForList("SELECT * FROM "+table+" WHERE "+key+"=? AND video_id=? FOR UPDATE",item,video);
        if(current.isEmpty()) throw missing();
        if(((Number)videos.get(0).get("user_id")).longValue()!=actor && ((Number)current.get(0).get("user_id")).longValue()!=actor)
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        if(source.equals("DANMAKU")) {
            var original=current.get(0);
            long sender=((Number)original.get("user_id")).longValue();
            String hash=DeletedBullets.fingerprint(video,sender,(String)original.get("content"),(String)original.get("color"),((Number)original.get("playback_time")).doubleValue());
            db.update("INSERT INTO deleted_bullet(bullet_id,video_id,user_id,payload_hash) VALUES(?,?,?,?)",item,video,sender,hash);
        }
        Set<Long> ids=new LinkedHashSet<>();
        var pending=new ArrayDeque<Long>(); pending.add(item);
        while(!pending.isEmpty()) {
            long id=pending.remove();
            if(!ids.add(id)) continue;
            if(ids.size()>10000) throw new BusinessException(ErrorCode.OPERATION_ERROR,"回复数量过多，请联系管理员处理");
            if(source.equals("COMMENT")) pending.addAll(db.queryForList("SELECT comment_id FROM comment WHERE video_id=? AND parent_comment_id=? FOR UPDATE",Long.class,video,id));
        }
        for(long id:ids) {
            db.update("DELETE FROM feedback_reaction WHERE source=? AND item_id=?",source,id);
            db.update("DELETE FROM "+table+" WHERE "+key+"=? AND video_id=?",id,video);
        }
        String count=source.equals("COMMENT")?"comment_count":"bullet_count";
        var remaining = new java.util.concurrent.atomic.AtomicLong();
        db.query("SELECT "+key+" FROM "+table+" WHERE video_id=? FOR UPDATE",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> remaining.incrementAndGet(), video);
        if(db.update("UPDATE video_stats SET "+count+"=?"+(source.equals("DANMAKU")?",bullet_revision=bullet_revision+1":"")+" WHERE video_id=?",remaining.get(),video)!=1)
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        db.update("UPDATE video SET update_time=GREATEST(CURRENT_TIMESTAMP(3),TIMESTAMPADD(MICROSECOND,1000,update_time)) WHERE video_id=?",video);
        invalidate(video);
    }
    private void invalidate(long video) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { redis.delete("videoDetails:"+video); }
                catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(CreatorWorkspace.class).warn("creator_cache_invalidation_failed video={}",video); }
            }
        });
    }
}
