package com.orangecode.tianmu.agentbridge;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.orangecode.tianmu.client.CanalClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackCanalCaptureTest {
    @Test void bothSourcesAndDeletesAreDurableBeforeCanalCanAcknowledge(){
        var work=mock(FeedbackVectorWork.class);
        @SuppressWarnings("unchecked") ObjectProvider<FeedbackVectorWork> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(work);
        var client=new CanalClient(null,null,null,null,provider);
        for(var type:List.of(CanalEntry.EventType.INSERT,CanalEntry.EventType.UPDATE,CanalEntry.EventType.DELETE)){
            var column=CanalEntry.Column.newBuilder().setName("video_id").setValue("1001").build();
            var row=CanalEntry.RowData.newBuilder().addBeforeColumns(column).addAfterColumns(column).build();
            var entry=CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA).setHeader(CanalEntry.Header.newBuilder().setSchemaName("tianmu").setTableName("comment")).setStoreValue(CanalEntry.RowChange.newBuilder().setEventType(type).addRowDatas(row).build().toByteString()).build();
            ReflectionTestUtils.invokeMethod(client,"handleMessage",List.of(entry));
        }
        verify(work,times(3)).changed(1001);
        doThrow(new IllegalStateException("database unavailable")).when(work).changed(1001);
        var column=CanalEntry.Column.newBuilder().setName("video_id").setValue("1001").build();
        var entry=CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA).setHeader(CanalEntry.Header.newBuilder().setTableName("bullet")).setStoreValue(CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.INSERT).addRowDatas(CanalEntry.RowData.newBuilder().addAfterColumns(column)).build().toByteString()).build();
        assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(client,"handleMessage",List.of(entry)));
    }
}
