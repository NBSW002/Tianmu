package com.orangecode.tianmu.service.impl;

import java.util.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.mapper.BulletMapper;
import com.orangecode.tianmu.model.dto.bullet.DeleteBulletRequest;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.model.entity.Bullet;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;
import com.orangecode.tianmu.service.BulletService;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class BulletServiceImpl extends ServiceImpl<BulletMapper, Bullet> implements BulletService {
    @Resource private com.orangecode.tianmu.application.CreatorWorkspace workspace;
    @Resource private com.orangecode.tianmu.application.CurrentUser currentUser;
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Resource private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Resource private com.fasterxml.jackson.databind.ObjectMapper json;
    @Resource
    private com.orangecode.tianmu.service.BulletIngestionService ingestion;


@Override
    @Transactional(rollbackFor = Exception.class)
    public void saveBulletToMySQL(SendBulletRequest sendBulletRequest) {
        ingestion.persist(sendBulletRequest);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteVideoBullet(DeleteBulletRequest input) {
        if(input==null || input.getBulletId()==null || input.getVideoId()==null) throw new com.orangecode.tianmu.exception.BusinessException(ErrorCode.PARAMS_ERROR);
        long actor = currentUser.requireMatchingUser(input.getUserId());
        workspace.deleteFeedback(actor,"DANMAKU",input.getBulletId(),input.getVideoId());
        return true;
    }



    @Override
    public List<OnlineBulletResponse> getBulletList(Long videoId) {
        Long version = revision(videoId);
        String key = version == null ? null : cacheKey(videoId);
        if (key != null) {
            try {
                String cached = redis.opsForValue().get(key);
                if (cached != null) {
                    var entry = json.readValue(cached, CachedBullets.class);
                    if (entry.version() == version.longValue() && entry.items() != null) return entry.items();
                }
            } catch (Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("bullet_cache_read_failed video={}", videoId); }
        }
        var bullets = this.list(new QueryWrapper<Bullet>().eq("video_id",videoId).orderByAsc("playback_time","bullet_id"));
        var result = new ArrayList<OnlineBulletResponse>();
        for (var bullet : bullets) {
            var item = new OnlineBulletResponse();
            item.setBulletId(bullet.getBulletId().toString()); item.setUserId(bullet.getUserId().toString());
            item.setText(bullet.getContent()); item.setPlaybackTime(bullet.getPlaybackTime());
            result.add(item);
        }
        if (key != null && Objects.equals(version, revision(videoId))) {
            try { redis.opsForValue().set(key, json.writeValueAsString(new CachedBullets(version, result)), java.time.Duration.ofMinutes(10)); }
            catch (Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("bullet_cache_write_failed video={}", videoId); }
        }
        return result;
    }

    private Long revision(Long videoId) {
        var versions = jdbc.queryForList("SELECT bullet_revision FROM video_stats WHERE video_id=?", Long.class, videoId);
        return versions.isEmpty() ? null : versions.get(0);
    }
    private record CachedBullets(long version, List<OnlineBulletResponse> items) {}
    private String cacheKey(Long videoId) { return "video:" + videoId + ":bullets:v2"; }

    @Override
    public boolean bulletExists(Long bulletId) {
        return this.lambdaQuery().eq(Bullet::getBulletId, bulletId).exists();
    }

}




