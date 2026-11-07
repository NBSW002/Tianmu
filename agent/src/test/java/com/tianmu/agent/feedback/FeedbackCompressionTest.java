package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackCompressionTest {
    @Test void centeringFallsBackWhenOneNearMeanExpressionContainsMostOriginalFeedback(){
        var vectors=mock(FeedbackVectorIndex.class);when(vectors.dimension()).thenReturn(2);when(vectors.fetch(any(),anyList(),any())).thenReturn(Map.of());
        FeedbackEmbedding embedding=new FeedbackEmbedding(){public String version(){return "fixture-weight";}public int dimension(){return 2;}public List<List<Float>> embed(List<String> texts,CallControl c){return texts.stream().map(t->t.startsWith("中心")?List.of(1f,0f):List.of(.9998f,Integer.parseInt(t.substring(t.lastIndexOf('号')+1))%2==0?.02f:-.02f)).toList();}};
        var s=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);s.bucketPolicy(new FeedbackBucketProperties(5,.65,2,50,64,true,20,.01,.001,.30));
        var rows=new ArrayList<Unit>();for(int i=1;i<=100;i++)rows.add(unit(i,Source.COMMENT,"中心观点原文",0L));for(int i=0;i<20;i++)rows.add(unit(i+101,Source.COMMENT,"旁支观点编号"+i,0L));
        var c=control();var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        var result=s.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(120,0,0,0,120),List.of(s.prepare(Source.COMMENT,rows,c)),c,()->{});
        assertEquals(21,result.exactExpressions());assertEquals("RAW_COSINE",result.bucketMetric().mode());assertEquals("TOO_MANY_NEAR_MEAN_MEMBERS",result.bucketMetric().fallback());
        assertTrue(result.unclassified().isEmpty());assertEquals(120,result.clusters().stream().mapToInt(x->x.members().size()).sum());
    }
    @Test void fiveBucketProtocolHandlesSparseAndSingleSourceData(){
        var vectors=mock(FeedbackVectorIndex.class);var s=service(vectors);s.bucketPolicy(new FeedbackBucketProperties(5,.65,2,50,64));var c=control();
        var scope=new Request(request.query(),Set.of(Source.COMMENT,Source.DANMAKU),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        var manifest=new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64));
        var one=List.of(unit(1,Source.COMMENT,"画面非常清楚",3L));
        var sparse=s.compress(UUID.randomUUID(),owner,scope,manifest,new Counts(1,0,0,0,1),List.of(s.prepare(Source.COMMENT,one,c),s.prepare(Source.DANMAKU,List.of(),c)),c,()->{});
        assertTrue(sparse.clusters().isEmpty());assertEquals(List.of("C:1"),sparse.unclassified());assertEquals("画面非常清楚",sparse.unclassifiedExamples().get(0).text());
        var two=List.of(one.get(0),unit(2,Source.COMMENT,"画面看得很清晰",2L));
        var grouped=s.compress(UUID.randomUUID(),owner,scope,manifest,new Counts(2,0,0,0,2),List.of(s.prepare(Source.COMMENT,two,c),s.prepare(Source.DANMAKU,List.of(),c)),c,()->{});
        assertEquals(1,grouped.clusters().size());assertTrue(grouped.unclassified().isEmpty());assertEquals(2,grouped.inputs().get(0).groups().get(0).messages());
    }
    @Test void contextDependentRepliesDoNotBorrowAnotherParentsMeaning(){
        var s=service(mock(FeedbackVectorIndex.class));var c=control();
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        var at=Instant.parse("2026-11-01T01:00:00Z");var rows=List.of(new Unit("C:1",Source.COMMENT,"1","非常赞同","父评论：音乐太吵",false,null,"u1",at,0L),new Unit("C:2",Source.COMMENT,"2","非常赞同","父评论：音乐很好",false,null,"u2",at,0L));
        var material=s.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(2,0,0,0,2),List.of(s.prepare(Source.COMMENT,rows,c)),c,()->{});
        assertTrue(material.clusters().isEmpty());assertEquals(Set.of("C:1","C:2"),Set.copyOf(material.unclassified()));assertTrue(material.inputs().isEmpty());
    }
    @Test void byteLimitsUseTheExactProductionProjectionIncludingEscapesAndGroupMetadata()throws Exception {
        var s=service(mock(FeedbackVectorIndex.class));var c=control();
        var row=new Unit("C:1",Source.COMMENT,"1","abc"+"\"".repeat(297),"\\".repeat(600),false,null,"viewer",Instant.parse("2026-11-01T01:00:00Z"),1L);
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.VERSION);
        var material=s.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(1,0,0,0,1),List.of(s.prepare(Source.COMMENT,List.of(row),c)),c,()->{});
        var projected=material.inputs().stream().flatMap(i->i.groups().stream()).map(g->FeedbackModelProjection.group(g,json)).toList();
        assertEquals(json.writeValueAsBytes(projected).length,material.modelBytes());
        assertTrue(material.modelBytes()>json.writeValueAsBytes(material.clusters().get(0).representatives().stream().map(FeedbackCompression.Representative::text).toList()).length);
        assertFalse(json.writeValueAsString(projected).contains("originalIds"));
    }
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AccessScope owner=new AccessScope("t","u",Set.of(1001L));
    final Request request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),Set.of(Source.COMMENT,Source.DANMAKU),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.VERSION);
    CallControl control(){return new CallControl(Instant.now().plusSeconds(10),new CancellationToken());}
    Unit unit(int id,Source source,String text,Long likes){return new Unit((source==Source.COMMENT?"C:":"D:")+id,source,""+id,text,"",false,source==Source.DANMAKU?1000L:null,"u"+id,Instant.parse("2026-11-01T01:00:00Z"),likes);}
    FeedbackCompression service(FeedbackVectorIndex vectors){
        when(vectors.dimension()).thenReturn(3);when(vectors.fetch(any(),anyList(),any())).thenReturn(Map.of());
        FeedbackEmbedding embedding=new FeedbackEmbedding(){public String version(){return "fixture";}public int dimension(){return 3;}public List<List<Float>> embed(List<String> texts,CallControl c){return texts.stream().map(t->List.of(1f,0f,0f)).toList();}};
        return new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);
    }
    @Test void jointClustersKeepSourceReservationsAndChooseLikesWithoutPassingAllOriginals() throws Exception {
        var vectors=mock(FeedbackVectorIndex.class);var s=service(vectors);var comments=new ArrayList<Unit>();var danmaku=new ArrayList<Unit>();
        comments.add(unit(1,Source.COMMENT,"背景音乐盖住人声了",120L));comments.add(unit(2,Source.COMMENT,"音乐太大听不清楚",90L));
        for(int i=3;i<103;i++)comments.add(unit(i,Source.COMMENT,"音乐太大听不清楚",1L));
        danmaku.add(unit(200,Source.DANMAKU,"手机外放听不清楚",null));
        var c=control();var material=s.compress(UUID.randomUUID(),owner,request,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(102,1,0,0,103),List.of(s.prepare(Source.COMMENT,comments,c),s.prepare(Source.DANMAKU,danmaku,c)),c,()->{});
        assertEquals(1,material.clusters().size());assertEquals(2,material.representativeCount());assertEquals(103,material.clusters().get(0).members().size());
        assertEquals(Set.of("C:1","D:200"),new HashSet<>(material.clusters().get(0).representatives().stream().map(FeedbackCompression.Representative::id).toList()));
        var text=material.inputs().stream().flatMap(i->i.groups().stream()).map(FeedbackModels.Group::text).toList().toString();
        assertTrue(text.contains("背景音乐盖住人声了"));assertFalse(text.contains("音乐太大听不清楚"));
        assertEquals(103,material.inputs().stream().flatMap(i->i.groups().stream()).mapToLong(FeedbackModels.Group::messages).sum());
        verify(vectors,never()).search(any(),anyList(),anyInt(),any());
    }
    @Test void filtersAreCountedAndDoNotBecomeModelEvidence(){
        var s=service(mock(FeedbackVectorIndex.class));var source=s.prepare(Source.COMMENT,List.of(unit(1,Source.COMMENT,"太吵",99L),unit(2,Source.COMMENT,"😀",0L),unit(3,Source.COMMENT,"人声听不清",0L)),control());
        assertEquals(List.of("C:3"),source.eligible().stream().map(Unit::id).toList());assertEquals(2,source.excluded().size());
    }
    @Test void noVectorServiceCannotMasqueradeAsCompression(){
        var s=new FeedbackCompression(null,null,FeedbackCompressionProperties.defaults(),json);
        assertEquals("VECTOR_REQUIRED",assertThrows(AgentException.class,()->s.compress(UUID.randomUUID(),owner,request,null,new Counts(0,0,0,0,0),List.of(),control(),()->{})).code());
    }
    @Test void allMandatoryClustersFitBeforeAnyOptionalRepresentative(){
        var vectors=mock(FeedbackVectorIndex.class);when(vectors.dimension()).thenReturn(3);when(vectors.fetch(any(),anyList(),any())).thenReturn(Map.of());
        var values=Map.of("第一类表达甲",List.of(1f,0f,0f),"第一类表达乙",List.of(.94f,.34f,0f),"第二类表达甲",List.of(0f,1f,0f),"第二类表达乙",List.of(0f,.94f,.34f),"第三类表达甲",List.of(0f,0f,1f),"第三类表达乙",List.of(.34f,0f,.94f));
        FeedbackEmbedding embedding=new FeedbackEmbedding(){public String version(){return "fixture-quota";}public int dimension(){return 3;}public List<List<Float>> embed(List<String> texts,CallControl c){return texts.stream().map(values::get).toList();}};
        var policy=new FeedbackCompressionProperties(false,3,15000,.90,32,.75,2,3,3,.96,300,600,32768,67108864,512);
        var s=new FeedbackCompression(embedding,vectors,policy,json);var rows=new ArrayList<Unit>();int n=0;for(var text:List.of("第一类表达甲","第一类表达乙","第二类表达甲","第二类表达乙","第三类表达甲","第三类表达乙"))rows.add(unit(++n,Source.COMMENT,text,1L));
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.VERSION);var c=control();
        var material=s.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(6,0,0,0,6),List.of(s.prepare(Source.COMMENT,rows,c)),c,()->{});
        assertEquals(3,material.clusters().size());assertEquals(3,material.representativeCount());assertTrue(material.clusters().stream().allMatch(k->k.representatives().size()==1));
        s.bucketPolicy(new FeedbackBucketProperties(5,.90,2,50,64));
        var fiveScope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        var five=s.compress(UUID.randomUUID(),owner,fiveScope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(6,0,0,0,6),List.of(s.prepare(Source.COMMENT,rows,c)),c,()->{});
        assertEquals(3,five.clusters().size());assertEquals(3,five.representativeCount());assertTrue(five.clusters().stream().allMatch(k->k.representatives().size()==1));
    }
    @Test void contextExcerptHasSealedProvenanceAndBoundedCodePointOffsets(){
        var s=service(mock(FeedbackVectorIndex.class));String context="父评论原话😀".repeat(150);var u=new Unit("C:1",Source.COMMENT,"1","背景音乐盖住人声了",context,false,null,"viewer",Instant.parse("2026-11-01T01:00:00Z"),10L);
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.VERSION);var c=control();
        var material=s.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(1,0,0,0,1),List.of(s.prepare(Source.COMMENT,List.of(u),c)),c,()->{});
        var r=material.clusters().get(0).representatives().get(0);assertEquals(600,r.context().codePointCount(0,r.context().length()));assertEquals(600,r.contextProvenance().endCodePoint());assertEquals(context.codePointCount(0,context.length()),r.contextProvenance().originalCodePoints());assertEquals("C:1",r.contextProvenance().memberId());assertEquals("replyContext",r.contextProvenance().field());
    }
}
