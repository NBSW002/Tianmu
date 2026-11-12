package com.orangecode.tianmu.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.orangecode.tianmu.mapper.VideoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublishedVideoListsTest {
    @Test void allPublicListsAndSearchCandidatesExcludeWithdrawnDraftAndDeletedVideos() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(source);
        String schema=Files.readString(Path.of("deploy/site/business-schema.sql"));
        for(String table:List.of("user","video","video_stats","like","coin","favorite")) {
            int start=schema.indexOf("CREATE TABLE IF NOT EXISTS `"+table+"`");
            db.execute(schema.substring(start,schema.indexOf(") ENGINE",start))+")");
        }
        db.update("INSERT INTO `user`(user_id) VALUES(1),(2)");
        db.update("INSERT INTO video(video_id,user_id,title,category_id,status,is_delete) VALUES(11,1,'public',1,2,0),(12,1,'draft',1,1,0),(13,1,'withdrawn',1,0,0),(14,1,'deleted',1,2,1)");
        for(long video:List.of(11L,12L,13L,14L)) {
            db.update("INSERT INTO video_stats(video_id) VALUES(?)",video);
            for(String table:List.of("like","coin","favorite"))
                db.update("INSERT INTO `"+table+"` (`"+table+"_id`,video_id,user_id) VALUES(?,?,2)",video,video);
        }
        var configuration=new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(VideoMapper.class);
        var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setConfiguration(configuration); factory.setMapperLocations(new ClassPathResource("mapper/VideoMapper.xml"));
        try(var session=factory.getObject().openSession(true)) {
            var mapper=session.getMapper(VideoMapper.class);
            assertEquals(List.of(11L),mapper.selectVideoWithStats(0,20).stream().map(row->row.getVideoId()).toList());
            assertEquals(List.of(11L),mapper.recommendVideoList(1,99L).stream().map(row->row.getVideoId()).toList());
            assertEquals(List.of(11L),mapper.getSubmitVideoList(1L).stream().map(row->row.getVideoId()).toList());
            assertEquals(List.of(11L),mapper.getCategoryVideoList(1).stream().map(row->row.getVideoId()).toList());
            assertEquals(List.of(11L),mapper.getLikeVideoList(2L).stream().map(row->row.getVideoId()).toList());
            assertEquals(List.of(11L),mapper.getCoinVideoList(2L).stream().map(row->row.getVideoId()).toList());
            assertEquals(List.of(11L),mapper.getFavoriteVideoList(2L).stream().map(row->row.getVideoId()).toList());
            var lookup=new VideoLookup(mapper);
            assertEquals(Set.of(11L),lookup.publishedIds(List.of(11L,12L,13L,14L)));
            assertNull(mapper.getVideoDetails(12L));
            db.update("UPDATE video SET status=0 WHERE video_id=11");
            session.clearCache();
            assertTrue(mapper.selectVideoWithStats(0,20).isEmpty());
            assertTrue(lookup.publishedIds(List.of(11L)).isEmpty());
        } finally { db.execute("SHUTDOWN"); }
    }
}
