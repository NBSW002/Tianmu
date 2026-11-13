package com.orangecode.tianmu.application;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class BulletWindowsTest {
 @Test void denseWindowsUseExactCursorsAndVersionedBoundedPages() {
  var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
  db.execute("CREATE TABLE bullet(bullet_id BIGINT PRIMARY KEY,user_id BIGINT,video_id BIGINT,content VARCHAR(100),playback_time DOUBLE)");
  db.execute("CREATE TABLE video_stats(video_id BIGINT PRIMARY KEY,bullet_revision BIGINT)");db.update("INSERT INTO video_stats VALUES(99,1)");
  long first=9007199254740993L;
  var args=new ArrayList<Object[]>();for(int i=0;i<501;i++)args.add(new Object[]{first+i,1L,99L,"text:"+i,0d});args.add(new Object[]{first+501,1L,99L,"boundary",60d});
  db.batchUpdate("INSERT INTO bullet VALUES(?,?,?,?,?)",args);
  var redis=mock(StringRedisTemplate.class); @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
  var service=new BulletWindows(db,redis,new ObjectMapper(),mock(VideoLookup.class));
  var page=service.read(99,0,0,0);assertEquals(500,page.items().size());assertTrue(page.hasMore());assertEquals(Long.toString(first+499),page.nextId());
  var second=service.read(99,0,page.nextTime(),Long.parseLong(page.nextId()));assertEquals(1,second.items().size());assertFalse(second.hasMore());
  assertEquals("boundary",service.read(99,1,60,0).items().get(0).getText());
  assertThrows(RuntimeException.class,()->service.read(99,0,Double.NaN,0));
  var capture=org.mockito.ArgumentCaptor.forClass(String.class);verify(values).set(eq("video:99:bullets:window:0"),capture.capture(),any(java.time.Duration.class));
  when(values.get("video:99:bullets:window:0")).thenReturn(capture.getValue());
  db.update("DELETE FROM bullet WHERE bullet_id=?",first);db.update("UPDATE video_stats SET bullet_revision=2");
  assertEquals(Long.toString(first+1),service.read(99,0,0,0).items().get(0).getBulletId());
 }
}
