package com.orangecode.tianmu.application;

import io.minio.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportCoverImagesTest {
    @Test void embedsBoundedThumbnailFromStoredObjectWithoutUsingItsUrlHost() throws Exception {
        var minio=mock(MinioClient.class);var thumbnails=mock(MediaThumbnails.class);var stat=mock(StatObjectResponse.class);
        when(minio.statObject(any())).thenReturn(stat);when(stat.size()).thenReturn(300000L);when(stat.etag()).thenReturn("v1");
        when(thumbnails.get(anyString(),eq(640),eq(300000L),any())).thenReturn(new byte[]{1,2,3});
        var images=new ReportCoverImages(minio,"tianmu-upload",thumbnails);
        assertEquals("data:image/webp;base64,AQID",images.read("http://minio:9000/tianmu-upload/cover_file.jpg"));
        verify(minio).statObject(argThat(args->args.bucket().equals("tianmu-upload") && args.object().equals("cover_file.jpg")));
        verify(minio,never()).getObject(any());
    }
    @Test void ignoresForeignPathsAndActiveContentWithoutStorageAccess(){
        var minio=mock(MinioClient.class);var thumbnails=mock(MediaThumbnails.class);
        var images=new ReportCoverImages(minio,"tianmu-upload",thumbnails);
        for(String url:java.util.List.of("http://localhost/private.png","http://host/other/cover_file.jpg","data:image/svg+xml,xxx","http://host/tianmu-upload/cover_file.svg","http://host/tianmu-upload/../cover_file.jpg"))assertEquals("",images.read(url));
        verifyNoInteractions(minio,thumbnails);
    }
    @Test void unavailableOrOversizedOriginalDoesNotBreakTheReport() throws Exception {
        var minio=mock(MinioClient.class);var thumbnails=mock(MediaThumbnails.class);var stat=mock(StatObjectResponse.class);
        when(minio.statObject(any())).thenReturn(stat);when(stat.size()).thenReturn(300000L);when(stat.etag()).thenReturn("v1");
        var images=new ReportCoverImages(minio,"tianmu-upload",thumbnails);
        assertEquals("",images.read("http://minio:9000/tianmu-upload/cover_file.jpg"));
        verify(minio,never()).getObject(any());
        when(minio.statObject(any())).thenThrow(new IllegalStateException());
        assertEquals("",images.read("http://minio:9000/tianmu-upload/cover_file.jpg"));
    }
}
