package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackAnalysisTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AccessScope owner=new AccessScope("tenant","881",Set.of(1001L),"opaque-ref");
    final Request request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,10,20,0,0),LocalDateTime.of(2026,10,21,0,0)),Set.of(Source.COMMENT,Source.DANMAKU),Grounding.FEEDBACK_ONLY,null,null,"feedback-v1");
    FeedbackPolicy policy(){return new FeedbackPolicy(true,20000,67108864,24000,40,8,2000,16000000,Duration.ofHours(1),Duration.ofSeconds(30));}
    final class Fixture {
        final MemoryFeedbackJobStore store=new MemoryFeedbackJobStore();
        final FeedbackObjects objects=new FeedbackObjects(new FileObjectStore(directory),json);
        final AtomicBoolean authorized=new AtomicBoolean(true);final AtomicInteger exports=new AtomicInteger(),calls=new AtomicInteger();
        int total;java.util.function.Consumer<Input> inspect=input->{};
        final FeedbackSnapshotPort source=new FeedbackSnapshotPort(){
            public void authorize(AccessScope scope,long id){if(!authorized.get())throw new AgentException("AUTH_CONTEXT_EXPIRED","Credential unavailable");}
            public Receipt export(Request r,AccessScope o,CallControl c,java.util.function.Consumer<List<Unit>> sink){
                exports.incrementAndGet();var units=new ArrayList<Unit>();
                for(int i=1;i<=total;i++){
                    var s=i%2==0?Source.COMMENT:Source.DANMAKU;String original=""+((i+1)/2);
                    units.add(new Unit((s==Source.COMMENT?"C:":"D:")+original,s,original,"观众反馈"+i,"",false,s==Source.DANMAKU?1000L:null,""+i));
                    if(units.size()==100){sink.accept(List.copyOf(units));units.clear();}
                }
                if(!units.isEmpty())sink.accept(units);
                return new Receipt(Instant.parse("2026-10-21T00:00:00Z"),new Counts(total/2,total-total/2,0,0,total));
            }
        };
        final FeedbackModels models=new FeedbackModels(){
            public String modelName(){return "fixture";}
            public String policyHash(){return "fixture-v1";}
            public List<Proposal> analyze(Input input,CallControl control){
                inspect.accept(input);calls.incrementAndGet();
                ModelBudget.execute(control,"fixture",true,8192,()->json.createObjectNode().set("usage",json.createObjectNode().put("prompt_tokens",1).put("completion_tokens",1)));
                var ids=input.kind()==WorkKind.CLASSIFY?input.groups().stream().map(Group::id).toList():input.children().stream().map(Topic::id).toList();
                return List.of(new Proposal("T1","问题与反馈","保留输入的讨论依据及不同意见。","",ids));
            }
        };
        final FeedbackAnalysisService service=new FeedbackAnalysisService(store,objects,source,models,policy());
        FeedbackJobStore.Job start(int count){total=count;return service.submit(UUID.randomUUID(),owner,request,null);}
        FeedbackJobStore.Job finish(UUID id){for(int i=0;i<2500;i++){var j=store.get(id);if(!MemoryFeedbackJobStore.active(j.phase()))return j;service.advance(id);}return fail("Job did not converge");}
    }
    @Test void twentyThousandOriginalsAreFullyCoveredWithoutAnEverGrowingModelContext(){
        var f=new Fixture();var maxGroups=new AtomicInteger();var modelBytes=new AtomicInteger();
        f.inspect=input->{maxGroups.accumulateAndGet(input.groups().size(),Math::max);modelBytes.accumulateAndGet(f.objects.bytes(input),Math::max);};
        var job=f.start(20000);var finished=f.finish(job.id());assertEquals(Phase.READY,finished.phase(),finished.errorCode());
        var asset=f.service.asset(job.id(),owner,request);assertEquals(20000,asset.snapshot().counts().total());assertEquals(10000,asset.snapshot().counts().comments());
        assertEquals(10000,asset.snapshot().counts().danmaku());assertTrue(asset.snapshot().chunks().size()>1);
        assertEquals(20000,f.service.topics(asset).stream().mapToLong(Topic::messages).sum());assertEquals(40,maxGroups.get());
        assertTrue(modelBytes.get()<30000,"Each model receives a bounded batch, not the original transcript");assertTrue(f.calls.get()>500);
        assertEquals(f.calls.get()*2L,finished.chargedTokens());assertEquals(f.calls.get(),finished.modelCalls());assertEquals(1,f.exports.get());
        assertThrows(AgentException.class,()->f.service.asset(job.id(),new AccessScope("tenant","other",Set.of(1001L)),request));
        var otherRange=new Request(new DanmakuTypes.Query(1001,request.query().start().minusDays(1),request.query().end()),request.sources(),request.grounding(),null,null,"feedback-v1");
        assertThrows(AgentException.class,()->f.service.asset(job.id(),owner,otherRange));
        String topic=f.service.topics(asset).get(0).id();Map<String,Object> children=null;
        for(int depth=0;depth<32;depth++){
            children=f.service.read(asset,"children",topic,0,10);
            assertTrue(json.valueToTree(children).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<18000);
            if(children.containsKey("groups"))break;
            topic=(String)((Map<?,?>)((List<?>)children.get("topics")).get(0)).get("id");
        }
        assertTrue(children.containsKey("groups"));assertFalse(children.toString().contains("originalIds"));
        var originals=f.service.read(asset,"originals",topic,0,1);String original=(String)((Map<?,?>)((List<?>)originals.get("originals")).get(0)).get("id");
        var text=f.service.originalText(asset,topic,original,0,4096);assertFalse(text.toString().contains("userId"));assertFalse(text.toString().contains("user_id"));
        final String leaf=topic;assertThrows(AgentException.class,()->f.service.originalText(asset,leaf,"C:999999",0,100));
    }
    @Test void emptyFeedbackProducesARealCompletedAssetWithoutInventedTopicsOrModelCalls(){
        var f=new Fixture();var j=f.start(0);assertEquals(Phase.READY,f.finish(j.id()).phase());
        var asset=f.service.asset(j.id(),owner,request);assertTrue(f.service.topics(asset).isEmpty());assertEquals(0,f.calls.get());
    }
    @Test void cancellationDuringModelCallCannotPublishAResultOrReturnReady(){
        var f=new Fixture();var j=f.start(20);f.service.advance(j.id());f.inspect=i->f.service.cancel(j.id());f.service.advance(j.id());
        assertEquals(Phase.CANCELLED,f.store.get(j.id()).phase());assertNull(f.store.get(j.id()).asset());
    }
    @Test void parentCancellationStopsProviderDispatchWithoutWaitingForRunTerminalStorage(){
        var f=new Fixture();var j=f.start(40);f.service.advance(j.id());var parent=new CancellationToken();
        f.inspect=input->parent.cancel(StopReason.USER_CANCELLED);f.service.advance(j.id(),parent);
        assertEquals(Phase.CANCELLED,f.store.get(j.id()).phase());assertEquals(0,f.store.get(j.id()).modelCalls());assertNull(f.store.get(j.id()).asset());
    }
    @Test void lossOfAuthorizationPausesAndResumeKeepsTheSnapshotAndRequiresTheSameOwner(){
        var f=new Fixture();var j=f.start(40);f.service.advance(j.id());var snapshot=f.store.get(j.id()).snapshot();
        f.authorized.set(false);f.service.advance(j.id());assertEquals(Phase.WAITING_AUTH,f.store.get(j.id()).phase());assertEquals(0,f.calls.get());
        f.authorized.set(true);assertThrows(AgentException.class,()->f.service.resume(j.id(),new AccessScope("tenant","other",Set.of(1001L))));
        f.service.resume(j.id(),owner);assertEquals(Phase.READY,f.finish(j.id()).phase());assertEquals(snapshot,f.store.get(j.id()).snapshot());assertEquals(1,f.exports.get());
    }
    @Test void unknownProviderUsageStaysChargedAndARepeatedAttemptCannotDispatchTwice(){
        var f=new Fixture();var j=f.start(40);f.service.advance(j.id());var work=f.store.claim(j.id(),Instant.now(),Duration.ofSeconds(60));
        var budget=new FeedbackBudget(f.store,work);var c=new CallControl(Instant.now().plusSeconds(30),new CancellationToken(),budget,()->{});
        assertThrows(IllegalStateException.class,()->ModelBudget.execute(c,"fixture",true,9000,()->{throw new IllegalStateException("network lost");}));
        assertEquals(9000,f.store.get(j.id()).chargedTokens());assertEquals(1,f.store.get(j.id()).modelCalls());
        assertThrows(AgentException.class,()->ModelBudget.execute(c,"fixture",true,9000,()->json.createObjectNode()));
    }
    @Test void reportedOverspendIsRetainedAndCannotBecomeASuccessfulLastBatch(){
        var f=new Fixture();var j=f.start(40);f.service.advance(j.id());var work=f.store.claim(j.id(),Instant.now(),Duration.ofSeconds(60));
        var c=new CallControl(Instant.now().plusSeconds(30),new CancellationToken(),new FeedbackBudget(f.store,work),()->{});
        var error=assertThrows(AgentException.class,()->ModelBudget.execute(c,"fixture",true,8192,()->json.createObjectNode().set("usage",json.createObjectNode().put("prompt_tokens",16000001).put("completion_tokens",1))));
        assertEquals("MODEL_TOKEN_BUDGET_EXCEEDED",error.code());assertEquals(16000002,f.store.get(j.id()).chargedTokens());
    }
    @Test void staleWorkerCannotCommitAfterAnotherWorkerReclaimsItsLease(){
        var f=new Fixture();var j=f.start(20);f.service.advance(j.id());
        var first=f.store.claim(j.id(),Instant.now().minusSeconds(10),Duration.ofSeconds(1));
        var second=f.store.claim(j.id(),Instant.now(),Duration.ofSeconds(60));assertNotNull(second);assertTrue(second.fence()>first.fence());
        Blob fake=f.objects.put(j.id(),Map.of("test",true));assertFalse(f.store.complete(first,fake));assertTrue(f.store.complete(second,fake));assertFalse(f.store.complete(second,fake));
    }
    @Test void missingDuplicateAndUnknownClassificationMembersAreRejectedInsteadOfSilentlyMarkedOther(){
        var f=new Fixture();var planner=new FeedbackPlanner(f.objects,policy());
        var input=new Input(WorkKind.CLASSIFY,List.of(new Group("G1",Source.COMMENT,"问答","",false,null,1,List.of("C:1")),new Group("G2",Source.COMMENT,"反对","",false,null,1,List.of("C:2"))),List.of(),null);
        for(var ids:List.of(List.of("G1"),List.of("G1","G1","G2"),List.of("G1","G2","G3")))
            assertEquals("INVALID_ANALYSIS",assertThrows(AgentException.class,()->planner.validate("work",input,List.of(new Proposal("T1","主题","","",ids)))).code());
    }
}
