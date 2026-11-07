package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackVectorIndex.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackClustersTest {
    final AccessScope owner=new AccessScope("t","u",Set.of(1001L));
    final Request request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),Set.of(Source.DANMAKU),Grounding.FEEDBACK_ONLY,null,null,FeedbackPreparationService.VERSION);
    Unit unit(int id,String text,long ms){return new Unit("D:"+id,Source.DANMAKU,""+id,text,"",false,ms,"u",Instant.parse("2026-11-01T01:00:00Z"));}
    CallControl control(){return new CallControl(Instant.now().plusSeconds(10),new CancellationToken());}
    @Test void cachedVersionsAvoidEmbeddingAndStaleCandidatesCannotEnterSnapshot(){
        var vectors=mock(FeedbackVectorIndex.class);var embedding=mock(FeedbackEmbedding.class);when(embedding.version()).thenReturn("fixture-v1");
        var rows=List.of(unit(1,"没声音",1000),unit(2,"听不到",1100),unit(3,"😀",70000),unit(4,"没声音",1000));
        when(vectors.fetch(any(),anyList(),any())).thenAnswer(inv->{var found=new HashMap<Reference,List<Float>>();for(var ref:(List<Reference>)inv.getArgument(1))found.put(ref,List.of(1f,0f,0f));return found;});
        when(vectors.search(any(),anyList(),anyInt(),any())).thenReturn(List.of(new Candidate(Reference.from(rows.get(1)),0.99),new Candidate(Reference.from(rows.get(2)),0.99),new Candidate(Reference.from(unit(9,"过期",1000)),1)));
        var result=new FeedbackClusters(vectors,embedding).organize(UUID.randomUUID(),owner,request,Source.DANMAKU,rows,control(),()->fail("Cached vectors must not consume embedding budget"));
        assertEquals(Set.of("D:1","D:2","D:3","D:4"),new HashSet<>(result.ordered().stream().map(Unit::id).toList()));
        assertEquals(4,result.ordered().size());assertEquals(3,result.exactExpressions());assertEquals(2,result.clusters());
        verify(embedding,never()).embed(anyList(),any());verify(vectors,never()).upsert(any(),anyList(),any());
    }
    @Test void exactOnlyKeepsShortMessagesAndSeparatePlaybackContexts(){
        var rows=List.of(unit(1,"?",1000),unit(2,"?",20000),unit(3,"😀",1000));
        var result=new FeedbackClusters(null,null).organize(UUID.randomUUID(),owner,request,Source.DANMAKU,rows,control(),()->{});
        assertEquals(rows,result.ordered());assertEquals(3,result.exactExpressions());
    }
}
