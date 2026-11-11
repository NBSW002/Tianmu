package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackCompressionTest {
    @Test void rerankAnchorAndCandidatesKeep256CodePointsOfTextAndContext(){
        var embedding=mock(FeedbackEmbedding.class);
        var vectors=mock(FeedbackVectorIndex.class);
        when(embedding.dimension()).thenReturn(3);
        when(embedding.version()).thenReturn("excerpt-fixture");
        when(vectors.dimension()).thenReturn(3);
        when(vectors.fetchEntries(any(),anyList(),any())).thenReturn(Map.of());
        when(embedding.embed(anyList(),any())).thenAnswer(inv ->
                inv.<List<String>>getArgument(0).stream().map(t -> List.of(1f,0f,0f)).toList());
        var compression=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);
        var rows=new ArrayList<Unit>();
        for(int id=1;id<=4;id++)rows.add(new Unit("C:"+id,Source.COMMENT,""+id,
                "具体反馈"+id+"😀".repeat(300),"上文"+"父".repeat(300),false,null,"u"+id,Instant.now()));
        var c=control();
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.RERANK_VERSION);
        var plan=compression.planRerank(owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),
                new Counts(4,0,0,0,4),List.of(compression.prepare(Source.COMMENT,rows,c)),c,()->{});
        assertFalse(plan.buckets().isEmpty());
        var all=new ArrayList<FeedbackCompression.Representative>();
        for(var bucket:plan.buckets()){
            all.add(bucket.anchor());
            bucket.candidates().forEach(candidate -> all.add(candidate.representative()));
        }
        assertEquals(4,all.size());
        for(var representative:all){
            assertEquals(256,representative.text().codePointCount(0,representative.text().length()));
            assertEquals(256,representative.context().codePointCount(0,representative.context().length()));
            assertEquals(256,representative.textProvenance().endCodePoint());
            assertEquals(256,representative.contextProvenance().endCodePoint());
        }
    }

    @Test void filtersPureCheersBeforeAnalysisWhileRetainingPlaybackCounts(){
        var compression=new FeedbackCompression(null,null,FeedbackCompressionProperties.defaults(),json);
        var rows=List.of(unit(1,Source.DANMAKU,"666666",1000L),
                unit(2,Source.DANMAKU,"６６６！！！[笑哭]",2000L),
                unit(3,Source.DANMAKU,"哈哈哈哈",3000L),
                unit(4,Source.DANMAKU,"233333",4000L),
                unit(5,Source.DANMAKU,"666，这段转音太稳了",5000L),
                unit(6,Source.DANMAKU,"02:30的字幕错了",6000L));
        var part=compression.prepare(Source.DANMAKU,rows,control());
        assertEquals(List.of("D:5","D:6"),part.eligible().stream().map(Unit::id).toList());
        assertEquals(4,part.excluded().size());
        assertTrue(part.excluded().stream().allMatch(e->e.reason().equals("LOW_INFORMATION")));
        assertEquals(6L,part.stats().playback().values().stream().mapToLong(Long::longValue).sum());
    }
    @Test void keepsEvidenceWithoutInventingAnActionAndRejectsCheerOnlyTopics(){
        var compression=new FeedbackCompression(null,null,FeedbackCompressionProperties.defaults(),json);
        compression.tieredPolicy(new FeedbackTieredProperties(true,32768));
        var rows=List.of(unit(1,Source.COMMENT,"声音和形象有反差",0L));
        var material=compression.directMaterial(new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),
                new Counts(1,0,0,0,1),List.of(compression.prepare(Source.COMMENT,rows,control())),control());
        var input=material.inputs().get(0);
        var proposal=new FeedbackModels.Proposal("T1","形象反差","观众提到了声音与形象的反差。","",
                List.of("C:1"),new Selection(List.of("C:1"),List.of(),"COHERENT",true,"直接证据"));
        assertEquals(1,FeedbackRerankConfirmation.validate("test",input,List.of(proposal)).topics().size());
        var cheer=new FeedbackModels.Group("C:1",Source.COMMENT,"666666","",false,null,1,List.of("C:1"),"D1");
        var cheerInput=new FeedbackModels.Input(WorkKind.CLASSIFY,List.of(cheer),List.of(),null,null);
        assertTrue(FeedbackRerankConfirmation.validate("test",cheerInput,List.of(proposal)).topics().isEmpty());
    }

    @Test void repeatedTextAtDifferentTimesConsumesOnlyOneCandidateSlot(){
        var embedding=mock(FeedbackEmbedding.class);var vectors=mock(FeedbackVectorIndex.class);
        when(embedding.dimension()).thenReturn(3);when(embedding.version()).thenReturn("dedup-fixture");
        when(vectors.dimension()).thenReturn(3);when(vectors.fetchEntries(any(),anyList(),any())).thenReturn(Map.of());
        when(embedding.embed(anyList(),any())).thenAnswer(inv -> inv.<List<String>>getArgument(0).stream().map(t->List.of(1f,0f,0f)).toList());
        var compression=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);
        var rows=List.of(unit(1,Source.DANMAKU,"人声太小听不清",1000L),unit(2,Source.DANMAKU,"人声太小听不清",20000L),
                unit(3,Source.DANMAKU,"背景音乐太响了",40000L),unit(4,Source.DANMAKU,"背景音乐太响了",60000L));
        var c=control();var scope=new Request(request.query(),Set.of(Source.DANMAKU),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.RERANK_VERSION);
        var plan=compression.planRerank(owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),
                new Counts(0,4,0,0,4),List.of(compression.prepare(Source.DANMAKU,rows,c)),c,()->{});
        assertEquals(1,plan.buckets().size());var bucket=plan.buckets().get(0);
        assertEquals(1,bucket.candidates().size());
        assertNotEquals(bucket.anchor().text(),bucket.candidates().get(0).representative().text());
        assertEquals(4,plan.material().clusters().get(0).members().size());
    }

    @Test void filteringSeparatesPureInteractionsFromSubstantiveFeedback(){
        var compression=new FeedbackCompression(null,null,FeedbackCompressionProperties.defaults(),json);
        var rows=List.of(unit(1,Source.COMMENT,"[星星眼][星星眼]",0L),
                unit(2,Source.COMMENT,"@朋友甲 @朋友乙",0L),
                unit(3,Source.COMMENT,"@朋友甲 人声太小了[星星眼]",0L),
                unit(4,Source.COMMENT,"这个版本真好听[星星眼]",0L));
        var part=compression.prepare(Source.COMMENT,rows,control());
        assertEquals(List.of("C:3","C:4"),part.eligible().stream().map(Unit::id).toList());
        assertEquals(Set.of("EMOJI_ONLY","MENTION_ONLY"),
                new HashSet<>(part.excluded().stream().map(FeedbackCompression.Excluded::reason).toList()));
    }

    @Test void tieredDirectRetainsFullTextAndContextWithoutCallingProviders(){
        var embedding=mock(FeedbackEmbedding.class);
        var vectors=mock(FeedbackVectorIndex.class);
        when(embedding.dimension()).thenReturn(1024);
        when(vectors.dimension()).thenReturn(1024);
        var compression=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);
        compression.tieredPolicy(new FeedbackTieredProperties(true,32768));
        String text="画面切得太快了".repeat(50);
        var row=new Unit("C:1",Source.COMMENT,"1",text,"上文原话",false,null,"u1",Instant.now());
        var c=control();
        var material=compression.directMaterial(new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),
                new Counts(1,0,0,0,1),List.of(compression.prepare(Source.COMMENT,List.of(row),c)),c);
        assertNotNull(material);
        assertEquals("DIRECT_LLM",material.summary().get("route"));
        assertEquals(text,material.inputs().get(0).groups().get(0).text());
        assertEquals("上文原话",material.inputs().get(0).groups().get(0).replyContext());
        var proposal=new FeedbackModels.Proposal("T1","节奏太快","观众明确提出画面切换太快。","尝试延长对应镜头。",
                List.of("C:1"),new Selection(List.of("C:1"),List.of(),"COHERENT",true,"直接证据"));
        assertEquals(1,FeedbackRerankConfirmation.validate("direct",material.inputs().get(0),List.of(proposal)).topics().size());
        verify(embedding,never()).embed(anyList(),any());
        verify(vectors,never()).fetchEntries(any(),anyList(),any());
    }
    @Test void tieredRoutingUsesBytesAndIncludesPolicyInRevision(){
        var embedding=mock(FeedbackEmbedding.class);
        var vectors=mock(FeedbackVectorIndex.class);
        when(embedding.dimension()).thenReturn(1024);
        when(vectors.dimension()).thenReturn(1024);
        var compression=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);
        String previous=compression.materialRevision(FeedbackCompression.RERANK_VERSION);
        compression.tieredPolicy(new FeedbackTieredProperties(true,8192));
        assertNotEquals(previous,compression.materialRevision(FeedbackCompression.RERANK_VERSION));
        var row=new Unit("C:1",Source.COMMENT,"1","长评论".repeat(2000),"",false,null,"u1",Instant.now());
        var c=control();
        assertNull(compression.directMaterial(new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),
                new Counts(1,0,0,0,1),List.of(compression.prepare(Source.COMMENT,List.of(row),c)),c));
        verify(embedding,never()).embed(anyList(),any());
    }

    @Test void rerankPlanProducesFiveIndependentShortlistsWithSeparateAnchors(){
        var vectors=mock(FeedbackVectorIndex.class);
        when(vectors.dimension()).thenReturn(5);
        when(vectors.fetch(any(),anyList(),any())).thenReturn(Map.of());
        var embedding=mock(FeedbackEmbedding.class);
        when(embedding.dimension()).thenReturn(5);
        when(embedding.version()).thenReturn("fixture-five");
        when(embedding.embed(anyList(),any())).thenAnswer(call->call.<List<String>>getArgument(0).stream()
                .map(text->java.util.stream.IntStream.range(0,5).mapToObj(n->text.contains("方向"+n)?1f:0f).toList()).toList());
        var compression=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        var rows=new ArrayList<Unit>();
        for(int bucket=0;bucket<5;bucket++)for(int index=0;index<22;index++){
            rows.add(unit(bucket*22+index+1,Source.COMMENT,"方向"+bucket+"的具体反馈编号"+index,0L));
        }
        var c=control();
        var plan=compression.planRerank(owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),
                new Counts(110,0,0,0,110),List.of(compression.prepare(Source.COMMENT,rows,c)),c,()->{});
        assertEquals(5,plan.buckets().size());
        assertEquals(100,plan.buckets().stream().mapToInt(bucket->bucket.candidates().size()).sum());
        var scores=java.util.stream.IntStream.range(0,20).mapToObj(n->(double)n).toList();
        var ranking=new FeedbackReranker.Result(scores,null);
        int selected=0;
        for(var bucket:plan.buckets()){
            assertEquals(20,bucket.candidates().size());
            assertTrue(bucket.candidates().stream().noneMatch(candidate->candidate.representative().id().equals(bucket.anchor().id())));
            assertEquals(bucket.candidates().subList(0,3),bucket.evidence(null));
            assertEquals(List.of(bucket.candidates().get(19),bucket.candidates().get(18),bucket.candidates().get(17)),bucket.evidence(ranking));
            selected+=bucket.evidence(ranking).size();
        }
        assertEquals(15,selected);
        assertEquals(110,plan.material().clusters().stream().mapToInt(bucket->bucket.members().size()).sum());
    }
    @Test void provenanceSurvivesPrewarmingAndReportWritesWhileLegacyStaysUnknown(){
        var stored=new HashMap<String,FeedbackVectorIndex.Entry>();
        FeedbackVectorIndex vectors=new FeedbackVectorIndex(){
            public int dimension(){return 3;}
            public void upsert(Namespace ns,List<Entry> entries,CallControl c){for(var e:entries)stored.put(e.reference().key(ns),e);}
            public Map<Reference,Entry> fetchEntries(Namespace ns,List<Reference> refs,CallControl c){var found=new HashMap<Reference,Entry>();for(var ref:refs){var e=stored.get(ref.key(ns));if(e!=null)found.put(ref,e);}return found;}
            public List<Candidate> search(Namespace ns,List<Float> v,int limit,CallControl c){return List.of();}
            public void delete(Namespace ns,List<Reference> refs,CallControl c){}
        };
        var embedding=mock(FeedbackEmbedding.class);when(embedding.dimension()).thenReturn(3);when(embedding.version()).thenReturn("fixture");
        when(embedding.embed(anyList(),any())).thenAnswer(inv->inv.<List<String>>getArgument(0).stream().map(t->List.of(1f,0f,0f)).toList());
        var compression=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);
        var rows=List.of(unit(1,Source.COMMENT,"画面细节看得很清楚",0L),unit(2,Source.COMMENT,"背景音乐盖住了说话声",0L),unit(3,Source.COMMENT,"视频节奏安排得挺合适",0L));
        var ns=new FeedbackVectorIndex.Namespace(owner.tenantId(),owner.userId(),request.query().videoId(),Source.COMMENT,"fixture:representatives-text-v1");
        compression.warm(owner,request.query().videoId(),List.of(rows.get(0)),control(),()->{});
        var legacy=FeedbackVectorIndex.Reference.from(rows.get(1));stored.put(legacy.key(ns),new FeedbackVectorIndex.Entry(legacy,List.of(1f,0f,0f)));
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        compression.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(3,0,0,0,3),List.of(compression.prepare(Source.COMMENT,rows,control())),control(),()->{});
        assertEquals(FeedbackVectorIndex.Origin.PREWARM,stored.get(FeedbackVectorIndex.Reference.from(rows.get(0)).key(ns)).provenance().origin());
        assertEquals(FeedbackVectorIndex.Origin.UNKNOWN,stored.get(legacy.key(ns)).provenance().origin());
        var generated=stored.get(FeedbackVectorIndex.Reference.from(rows.get(2)).key(ns)).provenance();assertEquals(FeedbackVectorIndex.Origin.REPORT_ANALYSIS,generated.origin());assertNotNull(generated.generatedAt());assertEquals(ns.embeddingVersion(),generated.embeddingVersion());
        verify(embedding,times(2)).embed(anyList(),any());
    }
    @Test void traceRecordsOnlyActuallyCachedAndGeneratedVectors(){
        var vectors=mock(FeedbackVectorIndex.class,CALLS_REAL_METHODS);when(vectors.dimension()).thenReturn(3);
        when(vectors.fetch(any(),anyList(),any())).thenAnswer(call->{var refs=call.<List<FeedbackVectorIndex.Reference>>getArgument(1);return Map.of(refs.get(0),List.of(1f,0f,0f));});
        var embedding=mock(FeedbackEmbedding.class);when(embedding.dimension()).thenReturn(3);when(embedding.version()).thenReturn("fixture");when(embedding.embed(anyList(),any())).thenReturn(List.of(List.of(1f,0f,0f)));
        var compression=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);var recorded=new ArrayList<io.opentelemetry.sdk.trace.data.SpanData>();
        var exporter=new io.opentelemetry.sdk.trace.export.SpanExporter(){public io.opentelemetry.sdk.common.CompletableResultCode export(Collection<io.opentelemetry.sdk.trace.data.SpanData> data){recorded.addAll(data);return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();}public io.opentelemetry.sdk.common.CompletableResultCode flush(){return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();}public io.opentelemetry.sdk.common.CompletableResultCode shutdown(){return flush();}};
        try(var provider=io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()){
            var span=provider.get("test").spanBuilder("feedback.prepare").startSpan();
            try(var ignored=span.makeCurrent()){compression.warm(owner,request.query().videoId(),List.of(unit(1,Source.COMMENT,"画面很清晰甲",0L),unit(2,Source.COMMENT,"画面很清晰乙",0L)),control(),()->{});}finally{span.end();}
        }
        var attrs=com.tianmu.agent.harness.trace.TraceAttributes.from(recorded.get(0).getAttributes());assertEquals(1L,attrs.get("analysis.vector_cache_hits"));assertEquals(1L,attrs.get("analysis.vector_generated"));assertEquals(0L,attrs.get("analysis.vector_failed"));assertEquals(true,attrs.get("analysis.vector_counts_complete"));
    }
    @Test void warmedRecordsUseExactlyTheReportCacheKeys(){
        var cache=new HashMap<FeedbackVectorIndex.Reference,List<Float>>();var vectors=mock(FeedbackVectorIndex.class,CALLS_REAL_METHODS);
        when(vectors.dimension()).thenReturn(3);
        when(vectors.fetch(any(),anyList(),any())).thenAnswer(call->{var found=new HashMap<FeedbackVectorIndex.Reference,List<Float>>();for(var ref:call.<List<FeedbackVectorIndex.Reference>>getArgument(1))if(cache.containsKey(ref))found.put(ref,cache.get(ref));return found;});
        doAnswer(call->{for(var entry:call.<List<FeedbackVectorIndex.Entry>>getArgument(1))cache.put(entry.reference(),entry.vector());return null;}).when(vectors).upsert(any(),anyList(),any());
        var embedding=mock(FeedbackEmbedding.class);when(embedding.dimension()).thenReturn(3);when(embedding.version()).thenReturn("standard:2560-test");
        when(embedding.embed(anyList(),any())).thenAnswer(call->call.<List<String>>getArgument(0).stream().map(t->List.of(1f,0f,0f)).toList());
        var compression=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);
        var rows=List.of(unit(1,Source.COMMENT,"  画面很清晰  ",0L),unit(2,Source.COMMENT,"声音很清楚",0L));
        compression.warm(owner,request.query().videoId(),rows,control(),()->{});
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        compression.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(2,0,0,0,2),List.of(compression.prepare(Source.COMMENT,rows,control())),control(),()->fail("Warmed report must not dispatch embedding"));
        verify(embedding,times(1)).embed(anyList(),any());
        verify(vectors,atLeastOnce()).fetch(argThat(ns->ns.embeddingVersion().endsWith(":representatives-text-v1")),anyList(),any());
    }
    @Test void sourceAggregateNeverPushesAnOtherwiseValidObjectOverItsSizeLimit(@TempDir Path directory){
        var objects=new FeedbackObjects(new com.tianmu.agent.adapters.storage.FileObjectStore(directory),json);var excluded=new ArrayList<FeedbackCompression.Excluded>();
        for(long i=0;i<18000;i++)excluded.add(new FeedbackCompression.Excluded("D:"+(1000000000000000000L+i),"SHORT_TEXT"));
        var playback=new TreeMap<Long,Long>();for(long i=0;i<9999;i++)playback.put(i*1000,1L);
        var source=new FeedbackPreparationService.SourcePart(Source.DANMAKU,List.of(),excluded,new FeedbackCompression.SourceStats(Map.of("2026-11-08",1L),playback,0));
        assertTrue(objects.bytes(source)>1048576);var bounded=FeedbackPreparationService.boundedPart(objects,source);assertNull(bounded.stats());assertEquals(excluded,bounded.excluded());assertTrue(objects.bytes(bounded)<=1048576);
        var material=new FeedbackCompression.Material(FeedbackCompression.FIVE_VERSION,FeedbackCompressionProperties.defaults(),"revision",new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(0,18000,0,0,0),new Counts(0,0,0,0,0),excluded,0,List.of(),List.of(),0,0,List.of(),List.of(),null,source.stats());
        assertTrue(objects.bytes(material)>1048576);var reduced=FeedbackPreparationService.boundedMaterial(objects,material);assertNull(reduced.stats());assertEquals(excluded,reduced.excluded());assertTrue(objects.bytes(reduced)<=1048576);
    }
    @Test void fiveBucketSelectsThreeDistinctRepresentativesWhenAvailable(){
        var vectors=mock(FeedbackVectorIndex.class);when(vectors.dimension()).thenReturn(3);when(vectors.fetch(any(),anyList(),any())).thenReturn(Map.of());
        FeedbackEmbedding embedding=new FeedbackEmbedding(){public String version(){return "fixture-three";}public int dimension(){return 3;}public List<List<Float>> embed(List<String> texts,CallControl c){return texts.stream().map(t->List.of(1f,0f,0f)).toList();}};
        var s=new FeedbackCompression(embedding,vectors,FeedbackCompressionProperties.defaults(),json);var c=control();
        var rows=List.of(unit(1,Source.COMMENT,"画面很清晰甲",10L),unit(2,Source.COMMENT,"画面很清晰乙",9L),unit(3,Source.COMMENT,"画面很清晰丙",8L));
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        var result=s.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(3,0,0,0,3),List.of(s.prepare(Source.COMMENT,rows,c)),c,()->{});
        assertEquals(1,result.clusters().size());assertEquals(3,result.representativeCount());var text=result.inputs().get(0).groups().get(0).text();assertTrue(text.contains("画面很清晰甲"));assertTrue(text.contains("画面很清晰乙"));assertTrue(text.contains("画面很清晰丙"));
        var loose=new FeedbackCompressionProperties(true,3,15000,.90,32,.75,5,5,60,.96,300,600,32768,67108864,512);
        var fiveTarget=new FeedbackCompression(embedding,vectors,loose,json);var more=new ArrayList<>(rows);more.add(unit(4,Source.COMMENT,"画面很清晰丁",7L));more.add(unit(5,Source.COMMENT,"画面很清晰戊",6L));
        for(int i=6;i<=10;i++)more.add(unit(i,Source.COMMENT,"画面非常清晰编号"+i,(long)i));
        var bounded=fiveTarget.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(10,0,0,0,10),List.of(fiveTarget.prepare(Source.COMMENT,more,c)),c,()->{});assertEquals(8,bounded.representativeCount());
    }
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
    @Test void meaningfulRepliesAndDeicticPhrasesKeepTheirExplicitSubject(){
        var s=service(mock(FeedbackVectorIndex.class));var c=control();
        var scope=new Request(request.query(),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackCompression.FIVE_VERSION);
        var at=Instant.parse("2026-11-01T01:00:00Z");var rows=List.of(new Unit("C:1",Source.COMMENT,"1","这个背景音乐太响了","父评论：音乐怎么样",false,null,"u1",at,0L),new Unit("C:2",Source.COMMENT,"2","背景音乐盖住人声了","父评论：收音怎么样",false,null,"u2",at,0L));
        var material=s.compress(UUID.randomUUID(),owner,scope,new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64)),new Counts(2,0,0,0,2),List.of(s.prepare(Source.COMMENT,rows,c)),c,()->{});
        assertEquals(1,material.clusters().size());assertTrue(material.unclassified().isEmpty());assertTrue(material.clusters().get(0).representatives().stream().anyMatch(r->r.text().contains("这个背景音乐")));
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
        assertEquals(List.of("C:3"),source.eligible().stream().map(Unit::id).toList());assertEquals(2,source.excluded().size());assertEquals(3L,source.stats().daily().values().stream().mapToLong(Long::longValue).sum());
        var danmaku=s.prepare(Source.DANMAKU,List.of(unit(4,Source.DANMAKU,"太吵",0L),unit(5,Source.DANMAKU,"声音听不清",0L)),control());assertEquals(1,danmaku.excluded().size());assertEquals(2L,danmaku.stats().playback().get(1000L));
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
