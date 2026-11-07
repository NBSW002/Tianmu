package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedFeedbackSnapshotTest {
    @TempDir Path directory;
    final UUID run=UUID.randomUUID(), job=UUID.randomUUID();
    final AccessScope owner=new AccessScope("tenant","owner",Set.of(1001L),"opaque");
    final Request request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,10,20,0,0),
            LocalDateTime.of(2026,10,21,0,0)),Set.of(Source.COMMENT,Source.DANMAKU),Grounding.FEEDBACK_ONLY,null,null,"v1");
    final Instant at=request.query().start().atZone(DanmakuTypes.ZONE).toInstant();
    final Instant cutoff=at.plusSeconds(86400);
    final AtomicInteger exports=new AtomicInteger();
    final AtomicBoolean authorized=new AtomicBoolean(true);
    List<Unit> rows=new ArrayList<>(List.of(unit(Source.COMMENT,1,"哈哈","父评论：太好笑了",false,"u1"),
            unit(Source.DANMAKU,1,"没声音","",false,"u1"),unit(Source.COMMENT,2,"哈哈","父评论：太好笑了",false,"u2"),
            unit(Source.COMMENT,3,"说明","",true,"owner")));
    Counts receipt=new Counts(3,1,1,0,2);
    final FeedbackSnapshotPort source=new FeedbackSnapshotPort(){
        public void authorize(AccessScope scope,long video){if(!authorized.get())throw new AgentException("AUTH_CONTEXT_EXPIRED","Expired");}
        public Receipt export(Request r,AccessScope o,CallControl c,java.util.function.Consumer<List<Unit>> sink){
            exports.incrementAndGet();sink.accept(List.copyOf(rows));return new Receipt(cutoff,receipt);
        }
    };
    Unit unit(Source s,int id,String text,String context,boolean author,String user){
        return new Unit((s==Source.COMMENT?"C:":"D:")+id,s,""+id,text,context,author,
                s==Source.DANMAKU?1000L:null,user,at);
    }
    CallControl control(){return new CallControl(Instant.now().plusSeconds(30),new CancellationToken());}
    FeedbackObjects objects(){return new FeedbackObjects(new FileObjectStore(directory),new ObjectMapper());}
    SharedFeedbackSnapshot service(){return new SharedFeedbackSnapshot(source,objects(),FeedbackPolicy.defaults());}
    String code(Runnable action){return assertThrows(AgentException.class,action::run).code();}

    @Test void oneExportTwoPartitionsSurviveRestartAndConcurrentReads() throws Exception {
        var ref=service().materialize(job,run,owner,request,control());
        // Live data changes after export. Both tools must continue reading the frozen manifest.
        rows.clear();
        var restarted=service(); var executor=Executors.newFixedThreadPool(2);
        try {
            var comments=executor.submit(()->{var found=new ArrayList<Unit>();restarted.read(ref,run,owner,request,Source.COMMENT,control(),found::addAll);return found;});
            var danmaku=executor.submit(()->{var found=new ArrayList<Unit>();restarted.read(ref,run,owner,request,Source.DANMAKU,control(),found::addAll);return found;});
            assertEquals(List.of("C:1","C:2","C:3"),comments.get().stream().map(Unit::id).toList());
            assertEquals("父评论：太好笑了",comments.get().get(0).replyContext());
            assertEquals("没声音",danmaku.get().get(0).text());
            var m=restarted.resolve(ref,run,owner,request,control());
            assertEquals(cutoff,m.dataAsOf());assertEquals(2,m.counts().uniqueAudience());
            assertEquals(2,m.partitions().get(Source.COMMENT).counts().uniqueAudience());
            assertEquals(1,m.partitions().get(Source.DANMAKU).counts().uniqueAudience());
            assertEquals(1,exports.get());
        } finally {executor.shutdownNow();}
    }
    @Test void wrongOwnerRunScopeAndChangedTaskAreRejected(){
        var s=service();var ref=s.materialize(job,run,owner,request,control());
        var other=new AccessScope("tenant","other",Set.of(1001L),"opaque");
        assertEquals("RESULT_NOT_FOUND",code(()->s.resolve(ref,run,other,request,control())));
        assertEquals("RESULT_NOT_FOUND",code(()->s.resolve(ref,UUID.randomUUID(),owner,request,control())));
        var narrowed=new Request(request.query(),Set.of(Source.COMMENT),request.grounding(),null,null,"v1");
        assertEquals("TASK_CONSTRAINT_VIOLATION",code(()->s.resolve(ref,run,owner,narrowed,control())));
        var noVideo=new AccessScope("tenant","owner",Set.of(),"opaque");
        assertEquals("FORBIDDEN",code(()->s.resolve(ref,run,noVideo,request,control())));
        authorized.set(false);
        assertEquals("AUTH_CONTEXT_EXPIRED",code(()->s.read(ref,run,owner,request,Source.COMMENT,control(),x->fail())));
    }
    @Test void emptySelectedSourceIsAValidPartition(){
        rows=List.of(unit(Source.COMMENT,1,"求教程","",false,"u1"));receipt=new Counts(1,0,0,0,1);
        var s=service();var ref=s.materialize(job,run,owner,request,control());
        var p=s.read(ref,run,owner,request,Source.DANMAKU,control(),x->fail());
        assertEquals(0,p.counts().total());assertTrue(p.chunks().isEmpty());
    }
    @Test void unselectedSourceCannotBeRead(){
        var only=new Request(request.query(),Set.of(Source.COMMENT),request.grounding(),null,null,"v1");
        rows=List.of(unit(Source.COMMENT,1,"求教程","",false,"u1"));receipt=new Counts(1,0,0,0,1);
        var s=service();var ref=s.materialize(job,run,owner,only,control());
        assertEquals("TASK_CONSTRAINT_VIOLATION",code(()->s.read(ref,run,owner,only,Source.DANMAKU,control(),x->fail())));
    }
    @Test void receiptMismatchAndDuplicateIdentityNeverPublishAManifest(){
        receipt=new Counts(2,1,1,0,2);
        assertEquals("INVALID_EVIDENCE",code(()->service().materialize(job,run,owner,request,control())));
        rows.add(rows.get(0));
        assertEquals("INVALID_EVIDENCE",code(()->service().materialize(job,run,owner,request,control())));
    }
    @Test void halfOpenIntervalAndMissingTimestampAreEnforced(){
        var original=rows.get(0);
        for(Instant time:new Instant[]{at.minusNanos(1),cutoff,null}){
            rows=List.of(new Unit(original.id(),original.source(),original.originalId(),original.text(),original.replyContext(),
                    false,null,"u1",time));
            assertEquals("INVALID_EVIDENCE",code(()->service().materialize(job,run,owner,request,control())));
        }
    }
    @Test void cancellationAndDeadlineStopBeforeExport(){
        var c=control();c.cancellation().cancel(StopReason.USER_CANCELLED);
        assertEquals("CANCELLED",code(()->service().materialize(job,run,owner,request,c)));
        var expired=new CallControl(Instant.now().minusSeconds(1),new CancellationToken());
        assertEquals("DEADLINE_EXCEEDED",code(()->service().materialize(job,run,owner,request,expired)));
        assertEquals(0,exports.get());
    }
    @Test void tamperedPartitionFailsIntegrityCheck() throws Exception {
        var s=service();var ref=s.materialize(job,run,owner,request,control());
        var m=s.resolve(ref,run,owner,request,control());
        var chunk=m.partitions().get(Source.COMMENT).chunks().get(0);
        // Simulate disk corruption, bypassing the immutable adapter intentionally.
        java.nio.file.Files.writeString(directory.resolve(chunk.key()),"{}");
        assertThrows(AgentException.class,()->s.read(ref,run,owner,request,Source.COMMENT,control(),x->fail()));
    }
    @Test void largePartitionIsChunkedAndRevocationStopsFurtherDelivery(){
        rows=new ArrayList<>();
        for(int i=1;i<=80;i++)rows.add(unit(Source.COMMENT,i,"长".repeat(8000),"",false,"u"+i));
        receipt=new Counts(80,0,0,0,80);
        var s=service();var ref=s.materialize(job,run,owner,request,control());
        var p=s.resolve(ref,run,owner,request,control()).partitions().get(Source.COMMENT);
        assertTrue(p.chunks().size()>1);
        assertTrue(p.chunks().stream().allMatch(b->b.bytes()<=524288));
        var delivered=new AtomicInteger();
        assertEquals("AUTH_CONTEXT_EXPIRED",code(()->s.read(ref,run,owner,request,Source.COMMENT,control(),part->{
            delivered.addAndGet(part.size());authorized.set(false);
        })));
        assertTrue(delivered.get()>0 && delivered.get()<80);
    }
    @Test void quotaStopsSnapshotAndEveryObjectUsesTheRetentionJob(){
        var registered=new ArrayList<UUID>();
        var index=new FeedbackObjectIndex(){
            public void register(UUID id,Blob b){registered.add(id);}
            public List<Expired> expired(int limit){return List.of();}
            public void removed(String key){}
        };
        var objects=new FeedbackObjects(new FileObjectStore(directory),new ObjectMapper(),index);
        new SharedFeedbackSnapshot(source,objects,FeedbackPolicy.defaults()).materialize(job,run,owner,request,control());
        assertFalse(registered.isEmpty());assertTrue(registered.stream().allMatch(job::equals));
        var p=FeedbackPolicy.defaults();
        var limited=new FeedbackPolicy(1,p.maxSnapshotBytes(),p.batchBytes(),p.batchGroups(),p.maxModelCalls(),p.maxTokens(),p.timeout(),p.modelTimeout());
        assertEquals("FULL_ANALYSIS_TOO_LARGE",code(()->new SharedFeedbackSnapshot(source,objects,limited).materialize(job,run,owner,request,control())));
    }
}
