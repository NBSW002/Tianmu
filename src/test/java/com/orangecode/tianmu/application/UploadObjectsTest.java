package com.orangecode.tianmu.application;

import io.minio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UploadObjectsTest {
    @Test void cleanupKeepsPublishedObjectWhenStorageOriginChanges() throws Exception {
        var client=mock(MinioClient.class);var retained=mock(io.minio.messages.Item.class);var abandoned=mock(io.minio.messages.Item.class);
        when(retained.objectName()).thenReturn("upload_session_2.mp4");when(abandoned.objectName()).thenReturn("upload_session_1.mp4");
        when(client.listObjects(any())).thenAnswer(call->{
            ListObjectsArgs args=call.getArgument(0);
            return args.prefix().equals("upload_session_") ? java.util.List.of(new Result<>(retained),new Result<>(abandoned)) : java.util.List.of();
        });
        new UploadObjects(client,"uploads","http://new-storage:9000","/upload-storage").clean("session","http://old-storage:9000/uploads/upload_session_2.mp4");
        var removed=org.mockito.ArgumentCaptor.forClass(RemoveObjectArgs.class);verify(client).removeObject(removed.capture());
        assertEquals("upload_session_1.mp4",removed.getValue().object());
    }
    @Test void postPolicyBindsSizeAndHostGeneratedNamespaceWithoutNetworkCall() throws Exception {
        var client=MinioClient.builder().endpoint("http://127.0.0.1:19000").region("us-east-1")
                .credentials("test-access","test-secret-key-for-local-signing").build();
        var part=new UploadObjects(client,"uploads","http://127.0.0.1:19000","/upload-storage").sign("host-session",2,25);
        assertEquals("/upload-storage/uploads",part.url());
        assertEquals("uploads/host-session/chunks/2",part.fields().get("key"));
        var policy=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.util.Base64.getDecoder().decode(part.fields().get("policy")));
        assertTrue(policy.path("conditions").toString().contains("[\"content-length-range\",25,25]"));
    }
    @Test void wrongSizeCannotBeComposed() throws Exception {
        var client=mock(MinioClient.class);var stat=mock(StatObjectResponse.class);
        when(client.statObject(any())).thenReturn(stat);when(stat.size()).thenReturn(9L);
        var objects=new UploadObjects(client,"uploads","http://127.0.0.1:19000","/upload-storage");
        assertThrows(IllegalStateException.class,()->objects.compose("id",1,new UploadPolicy.Request("a".repeat(32),10,"mp4")));
        verify(client,never()).composeObject(any());verify(client,never()).removeObject(any());
    }
    @Test void mergedObjectRemainsCompatibleWithPublishedMediaAndVideoAnalysis() throws Exception {
        var client=mock(MinioClient.class);var stat=mock(StatObjectResponse.class);
        when(client.statObject(any())).thenReturn(stat);when(stat.size()).thenReturn(10L);when(stat.etag()).thenReturn("etag");
        var objects=new UploadObjects(client,"tianmu-upload","http://minio:9000","/upload-storage");
        String url=objects.compose("01234567-0123-0123-0123-012345678901",7,new UploadPolicy.Request("a".repeat(32),10,"mp4"));
        assertTrue(url.matches("http://minio:9000/tianmu-upload/[A-Za-z0-9_-]{8,128}\\.mp4"));
        verify(client,never()).removeObject(any());
    }
}
