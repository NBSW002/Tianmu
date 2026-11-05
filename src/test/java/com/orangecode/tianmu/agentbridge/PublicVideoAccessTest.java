package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicVideoAccessTest {
    @Test void publishedReadableDoesNotGrantCreatorAccessAndRevocationAppliesImmediately() {
        var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE video(video_id BIGINT PRIMARY KEY,user_id BIGINT,status INT,is_delete INT,title VARCHAR(100),duration DOUBLE)");
        db.update("INSERT INTO video VALUES(1,10,2,0,'public',30),(2,20,1,0,'draft',20),(3,20,0,0,'offline',20),(4,20,2,1,'deleted',20),(5,10,1,0,'own draft',20)");
        var service = new BusinessMetrics(db,new BridgeProperties("site",Set.of("normal"),"Asia/Shanghai",5));
        var viewer = new BusinessAuthentication.Principal("site","30");
        var author = new BusinessAuthentication.Principal("site","10");
        var all = Set.of(1L,2L,3L,4L,5L);
        assertEquals(Set.of(1L),service.readable(viewer,all));
        assertTrue(service.allowed(viewer,all).isEmpty());
        assertEquals(Set.of(1L,5L),service.readable(author,all));
        assertEquals(Set.of(1L,5L),service.allowed(author,all));
        assertEquals("public",service.page(viewer,1).title());
        for(long id:List.of(2L,3L,4L,5L,999L)) assertThrows(BridgeException.class,()->service.page(viewer,id));
        db.update("UPDATE video SET status=0 WHERE video_id=1");
        assertTrue(service.readable(viewer,all).isEmpty());
        assertThrows(BridgeException.class,()->service.page(viewer,1));
        assertEquals("public",service.page(author,1).title());
        db.update("UPDATE video SET is_delete=1 WHERE video_id=1");
        assertThrows(BridgeException.class,()->service.page(author,1));
        assertFalse(service.allowed(author,all).contains(1L));
    }
}
