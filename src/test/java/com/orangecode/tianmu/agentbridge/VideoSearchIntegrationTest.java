package com.orangecode.tianmu.agentbridge;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BUSINESS_SEARCH_IT",matches="true")
class VideoSearchIntegrationTest {
    @Test void authorScopeLiteralKeywordsPublicationBoundsAndPagination(){
        String url=System.getenv("BUSINESS_SEARCH_JDBC_URL");
        assertTrue(url.matches("jdbc:mysql://[^/]+/[^?]*_it(?:\\?.*)?"));
        var source=new DriverManagerDataSource(url,"root","ci-only-test-password");var db=new JdbcTemplate(source);
        db.execute("CREATE TABLE IF NOT EXISTS video(video_id BIGINT PRIMARY KEY,user_id BIGINT,title VARCHAR(300),duration DOUBLE,create_time DATETIME,is_delete INT)");
        db.update("DELETE FROM video");
        for(long id=1;id<=5;id++)db.update("INSERT INTO video VALUES(?,?,?,?,?,?)",id,id==4?2:1,"小鸭100% "+id,31,"2026-10-28 10:00:00",id==5?1:0);
        db.update("INSERT INTO video VALUES(6,1,'小鸭100% boundary',31,'2026-11-02 00:00:00',0)");
        var metrics=new BusinessMetrics(db,new BridgeProperties("fixture",Set.of("1"),"Asia/Shanghai",2));
        var principal=new BusinessAuthentication.Principal("fixture","1");
        var first=metrics.search(principal,"100%",LocalDate.of(2026,10,26),LocalDate.of(2026,11,2),0,2);
        assertEquals(List.of("3","2"),first.items().stream().map(BusinessMetrics.VideoCandidate::videoId).toList());
        assertTrue(first.hasMore());assertEquals(2,first.nextOffset());
        assertEquals(Instant.parse("2026-10-28T02:00:00Z"),first.items().get(0).publishedAt());assertEquals(31000,first.items().get(0).durationMs());
        var second=metrics.search(principal,"100%",LocalDate.of(2026,10,26),LocalDate.of(2026,11,2),2,2);
        assertEquals(List.of("1"),second.items().stream().map(BusinessMetrics.VideoCandidate::videoId).toList());assertFalse(second.hasMore());
        assertTrue(metrics.search(principal,"' OR 1=1 --",null,null,0,10).items().isEmpty());
        assertThrows(BridgeException.class,()->metrics.search(principal,"",null,null,0,21));
    }
}
