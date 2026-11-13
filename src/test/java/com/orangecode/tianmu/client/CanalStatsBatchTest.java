package com.orangecode.tianmu.client;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.orangecode.tianmu.esdao.*;
import com.orangecode.tianmu.model.es.VideoEs;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CanalStatsBatchTest {
 @Test void failedStatsFlushDoesNotContinuePastDeletionBarrier() {
  var videos=mock(VideoEsDao.class);var document=new VideoEs();document.setId(99L);
  when(videos.findById(99L)).thenReturn(Optional.of(document));when(videos.save(any())).thenThrow(new IllegalStateException("index unavailable"));
  @SuppressWarnings("unchecked") ObjectProvider<com.orangecode.tianmu.agentbridge.FeedbackVectorWork> work=mock(ObjectProvider.class);
  var client=new CanalClient(mock(com.alibaba.otter.canal.client.CanalConnector.class),mock(UserEsDao.class),videos,mock(StringRedisTemplate.class),work);
  assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(client,"handleMessage",List.of(entry("video_stats",CanalEntry.EventType.UPDATE,2),entry("video",CanalEntry.EventType.DELETE,0))));
  verify(videos,never()).deleteById(anyLong());
 }
 @Test void statsUseLatestValueButFlushBeforeVideoDelete() {
  var videos=mock(VideoEsDao.class);var redis=mock(StringRedisTemplate.class);var document=new VideoEs();document.setId(99L);
  when(videos.findById(99L)).thenReturn(Optional.of(document));
  @SuppressWarnings("unchecked") ObjectProvider<com.orangecode.tianmu.agentbridge.FeedbackVectorWork> work=mock(ObjectProvider.class);
  var client=new CanalClient(mock(com.alibaba.otter.canal.client.CanalConnector.class),mock(UserEsDao.class),videos,redis,work);
  ReflectionTestUtils.invokeMethod(client,"handleMessage",List.of(entry("video_stats",CanalEntry.EventType.UPDATE,1),entry("video_stats",CanalEntry.EventType.UPDATE,2),entry("video",CanalEntry.EventType.DELETE,0)));
  var order=inOrder(videos);order.verify(videos).findById(99L);order.verify(videos).save(document);order.verify(videos).deleteById(99L);
  assertEquals(2,document.getBulletCount());verify(videos,times(1)).save(document);
 }
 private CanalEntry.Entry entry(String table,CanalEntry.EventType type,int count) {
  var row=CanalEntry.RowData.newBuilder();
  var columns=List.of(col("video_id","99"),col("view_count","0"),col("bullet_count",Integer.toString(count)));
  if(type==CanalEntry.EventType.DELETE)row.addAllBeforeColumns(columns);else row.addAllAfterColumns(columns);
  return CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA).setHeader(CanalEntry.Header.newBuilder().setSchemaName("tianmu").setTableName(table))
      .setStoreValue(CanalEntry.RowChange.newBuilder().setEventType(type).addRowDatas(row).build().toByteString()).build();
 }
 private CanalEntry.Column col(String name,String value){return CanalEntry.Column.newBuilder().setName(name).setValue(value).build();}
}
