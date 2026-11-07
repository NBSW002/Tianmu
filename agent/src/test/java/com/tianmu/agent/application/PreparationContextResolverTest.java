package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.query.*;
import com.tianmu.agent.runtime.control.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparationContextResolverTest {
    final AccessScope owner=new AccessScope("test","alice",Set.of(1001L));
    final PreparationContextResolver.Stages stages=new PreparationContextResolver.Stages(){public <T>T call(String name,Supplier<T> action){return action.get();}};
    @Test void searchTimeoutStartsAfterPageMetadataAndCandidatesDoNotGrantScope() {
        var clock=new AtomicReference<>(Instant.now());var start=clock.get();
        VideoPagePort pages=(id,scope)->{clock.set(start.plusSeconds(6));return new VideoPagePort.Page("1001","可信标题",10000);};
        VideoSearchPort search=(input,scope,control)->{
            assertEquals(start.plusSeconds(14),control.deadline());
            return new VideoSearchPort.Page(List.of(new VideoSearchPort.Candidate("1002","外部候选",10000,start)),1,false);
        };
        var resolver=new PreparationContextResolver(pages,search);
        var request=new PreparationContextResolver.Request(UUID.randomUUID(),"分析我上周那个小鸭视频的评论","分析我上周那个小鸭视频的评论",LocalDate.now(),owner,MemoryTypes.View.empty(),new RequestContext("1001","不可信标题",99999L));
        var result=resolver.resolve(request,()->new CallControl(clock.get().plusSeconds(8),new CancellationToken()),stages);
        assertNotNull(result.lookup());assertNull(result.memory().environment());assertEquals(Set.of(1001L),owner.videoIds());
    }
    @Test void pageMetadataIsTrustedButOutOfDurationPlaybackIsDiscarded() {
        var resolver=new PreparationContextResolver((id,scope)->new VideoPagePort.Page("1001","可信标题",10000),null);
        var request=new PreparationContextResolver.Request(UUID.randomUUID(),"分析这个视频的评论","分析这个视频的评论",LocalDate.now(),owner,MemoryTypes.View.empty(),new RequestContext("1001","客户端标题",10000L));
        var result=resolver.resolve(request,()->new CallControl(Instant.now().plusSeconds(8),new CancellationToken()),stages);
        assertEquals("可信标题",result.memory().environment().videoTitle());assertNull(result.memory().environment().playbackMs());assertNull(result.lookup());
    }
}
