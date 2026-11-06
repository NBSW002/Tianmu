package com.orangecode.tianmu.web;

import com.orangecode.tianmu.application.MediaThumbnails;
import com.orangecode.tianmu.controller.MediaController;
import com.orangecode.tianmu.service.*;
import com.orangecode.tianmu.model.entity.File;
import com.orangecode.tianmu.model.entity.Video;
import com.orangecode.tianmu.model.entity.User;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaControllerTest {
    private final FileService files=mock(FileService.class);
    private final VideoService videos=mock(VideoService.class);
    private final UserService users=mock(UserService.class);
    private final LambdaQueryChainWrapper<File> fileQuery=query();
    private final LambdaQueryChainWrapper<Video> videoQuery=query();
    private final LambdaQueryChainWrapper<User> userQuery=query();
    @SuppressWarnings("unchecked") private static <T> LambdaQueryChainWrapper<T> query() {
        return mock(LambdaQueryChainWrapper.class, invocation -> {
            if (java.util.Set.of("eq","like","and","or").contains(invocation.getMethod().getName())) return invocation.getMock();
            return RETURNS_DEFAULTS.answer(invocation);
        });
    }
    private final MinioClient minio=mock(MinioClient.class);
    private final MediaThumbnails thumbnails=mock(MediaThumbnails.class);
    private final MediaController controller=new MediaController();
    private final String name="12345678-abcd1234.png";
    @BeforeEach void setup() {
        when(files.lambdaQuery()).thenReturn(fileQuery);when(videos.lambdaQuery()).thenReturn(videoQuery);when(users.lambdaQuery()).thenReturn(userQuery);
        ReflectionTestUtils.setField(controller,"fileService",files);ReflectionTestUtils.setField(controller,"videoService",videos);
        ReflectionTestUtils.setField(controller,"userService",users);ReflectionTestUtils.setField(controller,"minioClient",minio);
        ReflectionTestUtils.setField(controller,"thumbnails",thumbnails);ReflectionTestUtils.setField(controller,"bucketName","tianmu-upload");
    }
    @Test void unreferencedImagesCannotReadStorageOrCachedThumbnails() {
        assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.image(name,320)).getStatusCode().value());
        verifyNoInteractions(minio,thumbnails);
    }
    @Test void onlySupportedSizesAreAccepted() {
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.image(name,1920)).getStatusCode().value());
        verifyNoInteractions(minio,thumbnails);
    }
    @Test void authorizedDerivativeUsesOriginalVersionAndReturnsWebp() throws Exception {
        when(fileQuery.exists()).thenReturn(true);
        var stat=mock(StatObjectResponse.class);when(stat.etag()).thenReturn("version-1");when(stat.size()).thenReturn(1000L);
        when(minio.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        byte[] output={1,2,3};when(thumbnails.get(contains(name+":version-1:1000"),eq(640),eq(1000L),any())).thenReturn(output);
        var response=controller.image(name,640);assertEquals("image/webp",response.getHeaders().getContentType().toString());
        assertEquals(3,response.getHeaders().getContentLength());var bytes=new ByteArrayOutputStream();response.getBody().writeTo(bytes);assertArrayEquals(output,bytes.toByteArray());
        verify(minio,never()).getObject(any(GetObjectArgs.class));
    }
}
