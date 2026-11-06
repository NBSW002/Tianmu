package com.orangecode.tianmu.application;

import org.junit.jupiter.api.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MediaThumbnailsTest {
    private byte[] picture() throws Exception {
        var image=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB); var random=new Random(42);
        for(int y=0;y<720;y++)for(int x=0;x<1280;x++)image.setRGB(x,y,random.nextInt(0xFFFFFF));
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    private void encoderAvailable() throws Exception {
        try { var process=new ProcessBuilder("ffmpeg","-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Assumptions.assumeTrue(process.waitFor(5,TimeUnit.SECONDS)&&process.exitValue()==0);
        } catch(IOException e) { Assumptions.abort("FFmpeg integration requires the runtime encoder"); }
    }
    @Test void encodedWebpIsCachedAndSourceChangesInvalidateIt() throws Exception {
        encoderAvailable();byte[] input=picture();var service=new MediaThumbnails("ffmpeg");var reads=new AtomicInteger();
        MediaThumbnails.Source source=()->{reads.incrementAndGet();return new ByteArrayInputStream(input);};
        byte[] small=service.get("bucket/image:etag1",320,input.length,source);
        assertNotNull(small);assertEquals("RIFF",new String(small,0,4,StandardCharsets.US_ASCII));assertEquals("WEBP",new String(small,8,4,StandardCharsets.US_ASCII));
        assertTrue(small.length<input.length/5);
        assertSame(small,service.get("bucket/image:etag1",320,input.length,source));assertEquals(1,reads.get());
        assertNotNull(service.get("bucket/image:etag2",320,input.length,source));assertEquals(2,reads.get());
        assertNotNull(service.get("bucket/image:etag2",960,input.length,source));assertEquals(3,reads.get());
    }
    @Test void simultaneousRequestsShareOneEncoding() throws Exception {
        encoderAvailable();byte[] input=picture();var service=new MediaThumbnails("ffmpeg");var reads=new AtomicInteger();
        var pool=Executors.newFixedThreadPool(4);
        try {
            var results=new ArrayList<Future<byte[]>>();
            for(int i=0;i<4;i++)results.add(pool.submit(()->service.get("same",640,input.length,()->{reads.incrementAndGet();Thread.sleep(100);return new ByteArrayInputStream(input);})));
            byte[] first=results.get(0).get(12,TimeUnit.SECONDS);assertNotNull(first);
            for(var result:results)assertArrayEquals(first,result.get(12,TimeUnit.SECONDS));
            assertEquals(1,reads.get());
        } finally {pool.shutdownNow();}
    }
    @Test void invalidSizeAndOversizedSourcesNeverOpenStorage() {
        var service=new MediaThumbnails("ffmpeg");MediaThumbnails.Source source=()->{fail("source must not open");return null;};
        assertNull(service.get("image",200,10,source));assertNull(service.get("image",320,17*1024*1024,source));
    }
    @Test void unavailableEncoderAndInvalidImageFallBackToOriginal() throws Exception {
        var service=new MediaThumbnails("missing-tianmu-encoder");byte[] input=picture();
        assertNull(service.get("missing",320,input.length,()->new ByteArrayInputStream(input)));
        assertNull(service.get("invalid",320,3,()->new ByteArrayInputStream(new byte[]{1,2,3})));
    }
}
