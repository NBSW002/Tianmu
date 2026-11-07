package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.KnownTopic;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackTopicDirectoryTest {
    final FeedbackJobStore store=mock(FeedbackJobStore.class);
    final AtomicInteger reads=new AtomicInteger();
    final Map<String,byte[]> payloads=new HashMap<>();
    final FeedbackObjects objects=new FeedbackObjects(new com.tianmu.agent.runtime.store.ObjectStore(){
        public void put(String key,byte[] bytes){payloads.put(key,bytes);}
        public byte[] get(String key,long size,String hash){reads.incrementAndGet();return payloads.get(key);}
        public void delete(String key){payloads.remove(key);}
    },new ObjectMapper());
    final List<FeedbackJobStore.Work> rows=new ArrayList<>();
    final FeedbackTopicDirectory directory=new FeedbackTopicDirectory(store,objects);
    FeedbackTopicDirectoryTest(){
        when(store.completedRange(any(),eq(0),anyInt(),anyInt())).thenAnswer(call->{
            UUID job=call.getArgument(0);int from=call.getArgument(2),count=call.getArgument(3);
            return rows.stream().filter(w->w.jobId().equals(job) && w.ordinal()>=from && w.ordinal()<from+count && w.state().equals("COMPLETE"))
                    .sorted(Comparator.comparingInt(FeedbackJobStore.Work::ordinal)).toList();
        });
    }
    FeedbackJobStore.Work batch(UUID job,int ordinal,String title){
        return batch(job,ordinal,new KnownTopic("T2",title,ordinal+1));
    }
    FeedbackJobStore.Work batch(UUID job,int ordinal,KnownTopic... entries){
        UUID id=UUID.randomUUID();var topics=Arrays.stream(entries).map(t->new Topic(id+":"+t.key(),t.title(),"完整原话观点","建议",List.of("G"+ordinal+":"+t.key()),t.messages(),0)).toList();
        var result=objects.put(job,new ResultNode(id.toString(),WorkKind.CLASSIFY,topics,topics.stream().mapToLong(Topic::messages).sum(),0));
        return new FeedbackJobStore.Work(id,job,WorkKind.CLASSIFY,0,ordinal,result,result,"COMPLETE",1,1,Instant.MAX);
    }
    FeedbackJobStore.Work claim(UUID job,int ordinal){return new FeedbackJobStore.Work(UUID.randomUUID(),job,WorkKind.CLASSIFY,0,ordinal,null,null,"RUNNING",1,1,Instant.MAX);}
    List<KnownTopic> before(UUID job,int ordinal){return directory.before(claim(job,ordinal),new CancellationToken());}

    @Test void warmDirectoryReadsEachAcceptedResultOnceAndRetryReadsNone(){
        UUID job=UUID.randomUUID();for(int i=0;i<125;i++)rows.add(batch(job,i,i==0?"首次标题":"后续改名"));
        assertTrue(before(job,0).isEmpty());
        for(int i=1;i<=125;i++){
            assertEquals(List.of(new KnownTopic("T2","首次标题",(long)i*(i+1)/2)),before(job,i));
            assertEquals(i,reads.get());
        }
        assertEquals(before(job,125),before(job,125));assertEquals(125,reads.get());
        verify(store,times(125)).completedRange(eq(job),eq(0),anyInt(),eq(1));
    }
    @Test void coldRestartAndEvictionRebuildInBoundedWindowsWithIdenticalOrderAndCounts(){
        UUID job=UUID.randomUUID();for(int i=0;i<130;i++)rows.add(batch(job,i,"标题"));
        var expected=before(job,130);assertEquals(130,reads.get());
        verify(store).completedRange(job,0,0,64);verify(store).completedRange(job,0,64,64);verify(store).completedRange(job,0,128,2);
        assertEquals(expected,new FeedbackTopicDirectory(store,objects).before(claim(job,130),new CancellationToken()));
        directory.invalidate(job);assertEquals(expected,before(job,130));assertEquals(390,reads.get());
        assertEquals(List.of(new KnownTopic("T2","标题",3)),before(job,2),"An earlier ordinal must not see future accepted counts");
    }
    @Test void interleavedThemesKeepFirstAppearanceOrderAndFirstAcceptedTitles(){
        UUID job=UUID.randomUUID();
        rows.add(batch(job,0,new KnownTopic("T2","第二主题先出现",4),new KnownTopic("T1","第一主题后出现",2)));
        rows.add(batch(job,1,new KnownTopic("T1","后续标题不替换首次标题",3),new KnownTopic("T3","新主题",7)));
        before(job,1);
        var expected=List.of(new KnownTopic("T2","第二主题先出现",4),new KnownTopic("T1","第一主题后出现",5),new KnownTopic("T3","新主题",7));
        assertEquals(expected,before(job,2));directory.invalidate(job);assertEquals(expected,before(job,2));
    }
    @Test void missingOrForeignResultsCannotAdvanceTheCachedPrefix(){
        UUID job=UUID.randomUUID();var first=batch(job,0,"有效");rows.add(first);before(job,1);
        var second=batch(job,1,"后续");var third=batch(job,2,"后续");rows.add(third);
        assertEquals("STORAGE_CORRUPT",assertThrows(AgentException.class,()->before(job,3)).code());
        rows.add(second);assertEquals(List.of(new KnownTopic("T2","有效",6)),before(job,3));
        directory.invalidate(job);
        rows.set(0,new FeedbackJobStore.Work(first.id(),job,WorkKind.CLASSIFY,0,0,first.input(),second.result(),"COMPLETE",1,1,Instant.MAX));
        assertEquals("STORAGE_CORRUPT",assertThrows(AgentException.class,()->before(job,1)).code());
        rows.set(0,first);assertEquals(List.of(new KnownTopic("T2","有效",1)),before(job,1));
    }
    @Test void jobsAreIsolatedAndCancelledRebuildDoesNotFetchOrPublishContext(){
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();rows.add(batch(first,0,"甲"));rows.add(batch(second,0,"乙"));
        assertEquals("甲",before(first,1).get(0).title());assertEquals("乙",before(second,1).get(0).title());
        var stopped=new CancellationToken();stopped.cancel(StopReason.USER_CANCELLED);directory.invalidate(first);reads.set(0);
        assertEquals("CANCELLED",assertThrows(AgentException.class,()->directory.before(claim(first,1),stopped)).code());
        assertEquals(0,reads.get());assertEquals("甲",before(first,1).get(0).title());assertEquals(1,reads.get());
    }
}
