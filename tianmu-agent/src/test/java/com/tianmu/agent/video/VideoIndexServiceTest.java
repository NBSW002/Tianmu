package com.tianmu.agent.video;

import com.tianmu.agent.video.index.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VideoIndexServiceTest {
    AccessScope owner=new AccessScope("t","u",Set.of(),null,Set.of(1L));
    VideoContentPort port=mock(VideoContentPort.class);VideoAnalysisService analysis=mock(VideoAnalysisService.class);
    MemoryVideoIndexStore store=new MemoryVideoIndexStore();VideoIndexService service;
    @BeforeEach void setup(){
        var beans=new StaticListableBeanFactory();beans.addBean("video",port);service=new VideoIndexService(store,beans.getBeanProvider(VideoContentPort.class),analysis);
        when(port.source(eq(1L),any(),any())).thenReturn(new VideoContent.Source(1,"v1",240001));
        when(analysis.analyze(eq(1L),anyLong(),anyLong(),anyString(),any(),any())).thenAnswer(call->{long start=call.getArgument(1),end=call.getArgument(2);return new VideoContent.Facts("video-content-v1",1,"v1",start,end,"sampled summary",List.of(),"speech","SAMPLED_FRAMES_AND_AUDIO_TRANSCRIPT",List.of(start),"vision",Instant.now());});
    }
    @AfterEach void close(){service.close();}
    VideoIndex awaitTerminal()throws Exception {for(int i=0;i<200;i++){var job=store.get(owner,1);if(job!=null&&!job.active())return job;Thread.sleep(10);}throw new AssertionError("Index did not finish");}
    @Test void partitionsEntireVideoAndIsolatesUsersAndVersions()throws Exception {
        var started=service.start(owner,1);var done=awaitTerminal();assertEquals("READY",done.status());assertEquals(3,done.chunks().size());assertEquals(240000,done.chunks().get(2).startMs());assertEquals(240001,done.chunks().get(2).endMs());assertEquals("speech",done.chunks().get(0).transcript());
        assertEquals(started.jobId(),service.start(owner,1).jobId());assertNull(service.get(new AccessScope("t","other",Set.of(),null,Set.of(1L)),1));assertThrows(AgentException.class,()->service.get(new AccessScope("t","u",Set.of()),1));
        when(port.source(eq(1L),any(),any())).thenReturn(new VideoContent.Source(1,"v2",240001));var stale=service.get(owner,1);assertEquals("STALE",stale.status());assertTrue(stale.chunks().isEmpty());
    }
    @Test void cancellationWinsAgainstLateWorkerAndRetryGetsNewGeneration()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(analysis.analyze(eq(1L),anyLong(),anyLong(),anyString(),any(),any())).thenAnswer(call->{entered.countDown();release.await(3,TimeUnit.SECONDS);throw new AgentException("MODEL_UNAVAILABLE","lost");});
        try{var job=service.start(owner,1);assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(job.jobId(),service.start(owner,1).jobId());service.cancel(owner,1);assertEquals("CANCELLED",store.get(owner,1).status());release.countDown();var next=service.start(owner,1);assertNotEquals(job.jobId(),next.jobId());assertEquals("FAILED",awaitTerminal().status());}finally{release.countDown();}
    }
    @Test void staleLeaseCanBeRetriedAndOldWorkerCannotOverwriteReplacement(){
        var old=new VideoIndex(UUID.randomUUID(),1,"v1",240001,"RUNNING",0,3,List.of(),null,Instant.now().minusSeconds(121),Instant.now().plusSeconds(60));store.start(owner,old);
        assertEquals("INDEX_INTERRUPTED",service.get(owner,1).errorCode());var next=service.start(owner,1);assertNotEquals(old.jobId(),next.jobId());assertFalse(store.replace(owner,old,old.state("READY",List.of(),null)));
    }
    @Test void sourceChangedMidProcessingNeverPublishesReady()throws Exception {
        when(analysis.analyze(eq(1L),anyLong(),anyLong(),anyString(),any(),any())).thenReturn(new VideoContent.Facts("video-content-v1",1,"v2",0,120000,"summary",List.of(),"","SAMPLED_FRAMES",List.of(0L),"vision",Instant.now()));
        service.start(owner,1);var failed=awaitTerminal();assertEquals("INDEX_SOURCE_CHANGED",failed.errorCode());assertTrue(failed.chunks().isEmpty());
    }
}
