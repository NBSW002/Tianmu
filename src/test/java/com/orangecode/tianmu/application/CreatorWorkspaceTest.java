package com.orangecode.tianmu.application;

import com.orangecode.tianmu.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreatorWorkspaceTest {
    JdbcTemplate db;
    CreatorWorkspace creator;
    WatchHistory history;
    StringRedisTemplate redis;
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(source);
        String schema=Files.readString(Path.of("deploy/site/business-schema.sql"));
        for(String table:List.of("user","user_stats","video","video_stats","comment","bullet","user_video_history")) {
            String marker="CREATE TABLE IF NOT EXISTS `"+table+"`";
            int start=schema.indexOf(marker);
            if(start<0) start=schema.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");
            db.execute(schema.substring(start,schema.indexOf(") ENGINE",start))+")");
        }
        String reactions=Files.readString(Path.of("deploy/site/migrations/20261108_feedback_reactions.sql"));
        db.execute(reactions.substring(reactions.indexOf("CREATE TABLE"),reactions.indexOf(") ENGINE"))+")");
        String tombstones=Files.readString(Path.of("deploy/site/migrations/20261112_creator_moderation.sql"));
        db.execute(tombstones.substring(0,tombstones.indexOf(") ENGINE")).replace(" CHARACTER SET ascii COLLATE ascii_bin","")+")");
        redis=mock(StringRedisTemplate.class);
        var manager=new DataSourceTransactionManager(source);
        var creatorProxy=new ProxyFactory(new CreatorWorkspace(db,redis)); creatorProxy.setProxyTargetClass(true);
        creatorProxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        creator=(CreatorWorkspace)creatorProxy.getProxy();
        var historyProxy=new ProxyFactory(new WatchHistory(db)); historyProxy.setProxyTargetClass(true);
        historyProxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        history=(WatchHistory)historyProxy.getProxy();
        db.update("INSERT INTO `user`(user_id,nickname) VALUES(1,'author'),(2,'viewer')");
        db.update("INSERT INTO user_stats(user_id,video_count) VALUES(1,1),(2,1)");
        db.update("INSERT INTO video(video_id,user_id,title,duration,status) VALUES(11,1,'first',60,2),(22,2,'other',60,2)");
        db.update("INSERT INTO video_stats(video_id) VALUES(11),(22)");
    }
    @AfterEach void close() { db.execute("SHUTDOWN"); }

    @Test void readsAndMutationsAreBoundToOwnerAndCannotBypassReview() {
        assertEquals(List.of("11"),creator.videos(1,1).items().stream().map(CreatorWorkspace.VideoItem::videoId).toList());
        assertThrows(BusinessException.class,()->creator.deleteVideo(2,11));
        assertThrows(BusinessException.class,()->creator.status(1,11,2,2));
        creator.edit(1,11,new CreatorWorkspace.Edit("new","details","first",""));
        assertEquals(1,db.queryForObject("SELECT status FROM video WHERE video_id=11",Integer.class));
        assertThrows(BusinessException.class,()->creator.edit(1,11,new CreatorWorkspace.Edit("stale","","first","")));
        creator.deleteVideo(1,11);
        assertEquals(0,db.queryForObject("SELECT video_count FROM user_stats WHERE user_id=1",Integer.class));
        assertThrows(BusinessException.class,()->creator.deleteVideo(1,11));
        assertTrue(creator.videos(1,1).items().isEmpty());
    }
    @Test void withdrawAndResubmitUseExpectedState() {
        creator.status(1,11,2,0);
        assertThrows(BusinessException.class,()->creator.status(1,11,2,0));
        creator.status(1,11,0,1);
        assertEquals(1,db.queryForObject("SELECT status FROM video WHERE video_id=11",Integer.class));
    }
    private void comments() {
        db.update("INSERT INTO comment(comment_id,video_id,user_id,parent_comment_id,content) VALUES(101,11,2,0,'root'),(102,11,2,101,'reply'),(103,11,2,102,'nested'),(201,22,2,0,'other')");
        db.update("UPDATE video_stats SET comment_count=3 WHERE video_id=11");
        db.update("INSERT INTO feedback_reaction(source,item_id,actor_id) VALUES('COMMENT',102,1)");
    }
    @Test void authorModeratesWholeThreadAndCountersAndReactionsFollow() {
        comments();
        assertEquals(3,creator.feedback(1,"COMMENT",1).items().size());
        assertThrows(BusinessException.class,()->creator.deleteFeedback(3,"COMMENT",101,null));
        assertThrows(BusinessException.class,()->creator.deleteFeedback(1,"COMMENT",101,22L));
        creator.deleteFeedback(1,"COMMENT",101,null);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM comment",Integer.class));
        assertEquals(0,db.queryForObject("SELECT comment_count FROM video_stats WHERE video_id=11",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM feedback_reaction",Integer.class));
        verify(redis).delete(List.of("videoDetails:11","video:11:bullet"));
    }
    @Test void failedCounterUpdateRollsBackThreadDeletion() {
        comments(); db.update("DELETE FROM video_stats WHERE video_id=11");
        assertThrows(BusinessException.class,()->creator.deleteFeedback(1,"COMMENT",101,null));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM comment",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM feedback_reaction",Integer.class));
        verifyNoInteractions(redis);
    }
    @Test void senderCanDeleteOwnDanmakuButCannotDeleteAnotherUsersRecord() {
        db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,playback_time) VALUES(301,11,2,'message',15)");
        String color=db.queryForObject("SELECT color FROM bullet WHERE bullet_id=301",String.class);
        assertEquals(15,creator.feedback(1,"DANMAKU",1).items().get(0).positionSeconds());
        assertThrows(BusinessException.class,()->creator.deleteFeedback(3,"DANMAKU",301,11L));
        creator.deleteFeedback(2,"DANMAKU",301,11L);
        assertTrue(creator.feedback(1,"DANMAKU",1).items().isEmpty());
        assertEquals(0,db.queryForObject("SELECT bullet_count FROM video_stats WHERE video_id=11",Integer.class));
        var event=new com.orangecode.tianmu.model.dto.bullet.SendBulletRequest();
        event.setBulletId(301L); event.setVideoId(11L); event.setUserId(2L);
        event.setContent("message"); event.setColor(color); event.setPlaybackTime(15d);
        var ingestion=new com.orangecode.tianmu.service.BulletIngestionService(db);
        ingestion.persist(event);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM bullet",Integer.class));
        event.setContent("changed payload");
        assertThrows(IllegalArgumentException.class,()->ingestion.persist(event));
    }
    @Test void historyIsPrivateUpsertsProgressAndRechecksPublication() {
        history.record(1,11,10); history.record(1,11,25); history.record(2,11,5);
        assertEquals(25,history.list(1,1).items().get(0).positionSeconds());
        assertTrue(java.time.Instant.parse(history.list(1,1).items().get(0).watchedAt()).isAfter(java.time.Instant.now().minusSeconds(60)));
        assertEquals(5,history.list(2,1).items().get(0).positionSeconds());
        assertThrows(BusinessException.class,()->history.record(1,11,Double.NaN));
        assertThrows(BusinessException.class,()->history.record(1,11,70));
        history.delete(1,11); assertTrue(history.list(1,1).items().isEmpty());
        assertEquals(1,history.list(2,1).items().size());
        creator.status(1,11,2,0);
        assertTrue(history.list(2,1).items().isEmpty());
        assertThrows(BusinessException.class,()->history.record(2,11,3));
        history.clear(2); assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_video_history",Integer.class));
    }
    @Test void pagesAreBoundedAndKeepLongIdsExact() {
        for(int i=0;i<21;i++) db.update("INSERT INTO video(video_id,user_id,title,duration,status) VALUES(?,1,'item',60,2)",9007199254740993L+i);
        assertEquals(20,creator.videos(1,1).items().size()); assertTrue(creator.videos(1,1).hasMore());
        assertEquals(2,creator.videos(1,2).items().size());
        assertTrue(creator.videos(1,1).items().stream().anyMatch(item->item.videoId().equals("9007199254741013")));
        assertThrows(BusinessException.class,()->creator.videos(1,0));
        assertThrows(BusinessException.class,()->creator.feedback(1,"OTHER",1));
    }
}
