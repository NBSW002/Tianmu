package com.orangecode.tianmu.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orangecode.tianmu.mapper.BulletMapper;
import com.orangecode.tianmu.model.entity.Bullet;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulletCacheTest {
 @Test void completeVersionedCacheIsUsedAndDatabaseChangesInvalidateIt() throws Exception {
  var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:bullet_cache;DB_CLOSE_DELAY=-1","sa",""));
  jdbc.execute("CREATE TABLE video_stats(video_id BIGINT PRIMARY KEY, bullet_revision BIGINT)");
  jdbc.update("INSERT INTO video_stats VALUES(99,1)");
  var mapper=mock(BulletMapper.class); var redis=mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
  when(redis.opsForValue()).thenReturn(values);
  var bullet=new Bullet(); bullet.setBulletId(123L); bullet.setUserId(1L); bullet.setContent("colon: preserved"); bullet.setPlaybackTime(1.5);
  when(mapper.selectList(any())).thenReturn(List.of(bullet));
  var service=new BulletServiceImpl(); ReflectionTestUtils.setField(service,"baseMapper",mapper);
  ReflectionTestUtils.setField(service,"jdbc",jdbc); ReflectionTestUtils.setField(service,"redis",redis); ReflectionTestUtils.setField(service,"json",new ObjectMapper());
  assertEquals("colon: preserved",service.getBulletList(99L).get(0).getText());
  var saved=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(values).set(eq("video:99:bullets:v2"),saved.capture(),any(java.time.Duration.class));
  when(values.get(anyString())).thenReturn(saved.getValue());
  assertEquals("colon: preserved",service.getBulletList(99L).get(0).getText());
  verify(mapper,times(1)).selectList(any());
  jdbc.update("UPDATE video_stats SET bullet_revision=2 WHERE video_id=99");
  when(mapper.selectList(any())).thenReturn(List.of());
  assertTrue(service.getBulletList(99L).isEmpty());
  verify(mapper,times(2)).selectList(any());
  clearInvocations(values);
  jdbc.update("UPDATE video_stats SET bullet_revision=3 WHERE video_id=99");
  when(mapper.selectList(any())).thenAnswer(call -> { jdbc.update("UPDATE video_stats SET bullet_revision=4 WHERE video_id=99"); return List.of(bullet); });
  assertEquals(1, service.getBulletList(99L).size());
  verify(values, never()).set(anyString(),anyString(),any(java.time.Duration.class));
 }
}
