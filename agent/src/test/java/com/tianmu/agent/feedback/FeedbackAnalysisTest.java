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
    FeedbackPolicy policy(){return new FeedbackPolicy(5000,67108864,24000,40,2000,16000000,Duration.ofHours(1),Duration.ofSeconds(30));}
    final class Fixture {
        final MemoryFeedbackJobStore store=new MemoryFeedbackJobStore();
        final AtomicInteger reads=new AtomicInteger();
        final FeedbackObjects objects=new FeedbackObjects(new com.tianmu.agent.runtime.store.ObjectStore(){
            final FileObjectStore delegate=new FileObjectStore(directory);
            public void put(String key,byte[] bytes){delegate.put(key,bytes);}
            public byte[] get(String key,long size,String hash){reads.incrementAndGet();return delegate.get(key,size,hash);}
            public void delete(String key){delegate.delete(key);}
        },json);
        final AtomicBoolean authorized=new AtomicBoolean(true);final AtomicInteger exports=new AtomicInteger(),calls=new AtomicInteger();
        int total;java.util.function.Consumer<Input> inspect=input->{};
        java.util.function.Function<Input,List<Proposal>> proposals;
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
                if(proposals!=null)return proposals.apply(input);
                return List.of(new Proposal("T1","问题与反馈","保留输入的讨论依据及不同意见。","",ids));
            }
        };
        final FeedbackAnalysisService service;
        Fixture(){this(policy());}
        Fixture(FeedbackPolicy execution){service=new FeedbackAnalysisService(store,objects,source,models,execution);}
        FeedbackJobStore.Job start(int count){total=count;return service.submit(UUID.randomUUID(),owner,request,null);}
        FeedbackJobStore.Job finish(UUID id){for(int i=0;i<2500;i++){var j=store.get(id);if(!MemoryFeedbackJobStore.active(j.phase()))return j;service.advance(id);}return fail("Job did not converge");}
    }
    @Test void rejectedModelAttemptReturnsItsErrorForTracingAndCanStillRecover()throws Exception {
        var f=new Fixture();var job=f.start(2);f.service.advance(job.id());
        f.proposals=input->{throw new AgentException("INVALID_ANALYSIS","fixture protocol rejection");};
        var failure=f.service.advance(job.id(),null,()->{});assertEquals("INVALID_ANALYSIS",failure.orElseThrow().code());
        assertTrue(MemoryFeedbackJobStore.active(f.store.get(job.id()).phase()));assertEquals(0,f.store.get(job.id()).completedWork());assertEquals(1,f.store.get(job.id()).modelCalls());
        f.proposals=null;Thread.sleep(2100);assertTrue(f.service.advance(job.id(),null,()->{}).isEmpty());
        assertEquals(Phase.READY,f.finish(job.id()).phase());assertEquals(2,f.store.get(job.id()).modelCalls());
    }
    @Test void exhaustedOutputBudgetFailsOnceInsteadOfRepeatingTheSamePaidRequest(){
        var f=new Fixture();var job=f.start(2);f.service.advance(job.id());
        f.proposals=input->{throw new AgentException("MODEL_OUTPUT_LIMIT","incomplete output");};
        var failure=f.service.advance(job.id(),null,()->{});assertEquals("MODEL_OUTPUT_LIMIT",failure.orElseThrow().code());
        assertEquals(Phase.FAILED,f.store.get(job.id()).phase());assertEquals("MODEL_OUTPUT_LIMIT",f.store.get(job.id()).errorCode());
        f.service.advance(job.id());assertEquals(1,f.calls.get());assertEquals(1,f.store.get(job.id()).modelCalls());assertEquals(0,f.store.get(job.id()).completedWork());
    }
    @Test void observationIndexSurvivesRestartAndDeepPagesReadOnlyIntersectingBatches() throws Exception {
        var f=new Fixture();
        f.proposals=input->{
            int n=f.calls.get()%3+1;var out=new ArrayList<Proposal>();
            for(int i=0;i<n;i++){
                var ids=new ArrayList<String>();for(int k=i;k<input.groups().size();k+=n)ids.add(input.groups().get(k).id());
                out.add(new Proposal("T"+(i+1),"观点"+i,"完整观察","建议",ids));
            }
            return out;
        };
        var job=f.start(240);assertEquals(Phase.READY,f.finish(job.id()).phase());
        var pagedStore=org.mockito.Mockito.spy(f.store);
        var restarted=new FeedbackAnalysisService(pagedStore,f.objects,f.source,f.models,policy());
        var asset=restarted.historicalAsset(job.id(),owner);
        assertEquals(List.of(2,3,1,2,3,1),asset.observationCounts());
        var all=f.store.completedLevel(job.id(),0).stream().flatMap(w->f.objects.get(w.result(),ResultNode.class).topics().stream()).map(Topic::id).toList();
        f.reads.set(0);
        var deep=restarted.notes(asset,9,3);
        assertEquals(all.subList(9,12),((List<Map<String,Object>>)deep.get("observations")).stream().map(t->t.get("id")).toList());
        assertEquals(2,f.reads.get());assertEquals(12,deep.get("next_offset"));assertEquals(true,deep.get("complete"));
        org.mockito.Mockito.verify(pagedStore).completedRange(job.id(),0,4,2);
        org.mockito.Mockito.verify(pagedStore,org.mockito.Mockito.never()).completedLevel(job.id(),0);
        f.reads.set(0);assertEquals(List.of(),restarted.notes(asset,100000,10).get("observations"));assertEquals(0,f.reads.get());

        // Simulate the exact old persisted JSON shape, not merely an in-memory constructor.
        var legacyJson=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(asset);legacyJson.remove("observationCounts");
        var legacy=json.treeToValue(legacyJson,Asset.class);assertNull(legacy.observationCounts());
        assertEquals(deep,restarted.notes(legacy,9,3));
        f.reads.set(0);restarted.notes(legacy,0,1);assertEquals(1,f.reads.get(),"A legacy first page must not load later batches");
        var seen=new ArrayList<Object>();int offset=0;
        while(true){var page=restarted.notes(asset,offset,2);((List<Map<String,Object>>)page.get("observations")).forEach(t->seen.add(t.get("id")));
            offset=(int)page.get("next_offset");if((boolean)page.get("complete"))break;}
        assertEquals(all,seen);
        var corrupt=new Asset(asset.jobId(),asset.producerRun(),asset.tenantId(),asset.userId(),asset.request(),asset.snapshot(),asset.roots(),asset.model(),asset.policyHash(),asset.video(),List.of(1,3,1,2,3,1));
        assertEquals("STORAGE_CORRUPT",assertThrows(AgentException.class,()->restarted.notes(corrupt,0,1)).code());
        org.mockito.Mockito.doReturn(List.of()).when(pagedStore).completedRange(job.id(),0,4,2);
        assertEquals("STORAGE_CORRUPT",assertThrows(AgentException.class,()->restarted.notes(asset,9,3)).code());
    }
    @Test void historicalReportsRemainReadableAfterModelAndPolicyUpgradeWithoutReanalysis(){
        var f=new Fixture();var job=f.start(4);assertEquals(Phase.READY,f.finish(job.id()).phase());int calls=f.calls.get();
        var upgraded=new FeedbackModels(){
            public String modelName(){return "fixture-v2";}public String policyHash(){return "policy-v2";}
            public List<Proposal> analyze(Input input,CallControl control){throw new AssertionError("Historical reads must not invoke a model");}
        };
        var reader=new FeedbackAnalysisService(f.store,f.objects,f.source,upgraded,policy());
        var old=reader.historicalAsset(job.id(),owner);assertEquals("fixture",old.model());assertEquals("fixture-v1",old.policyHash());
        assertEquals(4,old.snapshot().counts().total());assertFalse(reader.topics(old).isEmpty());
        assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->reader.asset(job.id(),owner,request)).code());
        assertEquals(calls,f.calls.get());assertEquals(1,f.exports.get());
        assertThrows(AgentException.class,()->reader.historicalAsset(job.id(),new AccessScope("tenant","other",Set.of(1001L))));
    }
    @Test void mergedChildrenReadOnlyTheRequestedMembersAndOutOfRangePagesReadNoChildren(){
        var f=new Fixture();var job=f.start(240);assertEquals(Phase.READY,f.finish(job.id()).phase());
        var asset=f.service.asset(job.id(),owner,request);var root=f.service.topics(asset).get(0);
        assertEquals(6,root.members().size());f.reads.set(0);
        var page=f.service.read(asset,"children",root.id(),3,1);
        assertEquals(root.members().get(3),((Map<?,?>)((List<?>)page.get("topics")).get(0)).get("id"));
        assertEquals(2,f.reads.get(),"One parent result and one selected child result");
        assertEquals(4,page.get("next_offset"));assertEquals(false,page.get("complete"));
        f.reads.set(0);var empty=f.service.read(asset,"children",root.id(),100,10);
        assertEquals(1,f.reads.get());assertEquals(6,empty.get("next_offset"));assertEquals(true,empty.get("complete"));
    }
    @Test void byteLimitedObservationAndChildPagesResumeWithoutSkippingTheWithheldItem(){
        var f=new Fixture();
        f.proposals=input->List.of(new Proposal("T1","观点","观".repeat(2400),"建".repeat(1200),input.groups().stream().map(Group::id).toList()));
        var job=f.start(120);assertEquals(Phase.READY,f.finish(job.id()).phase());var asset=f.service.asset(job.id(),owner,request);
        var root=f.service.topics(asset).get(0);
        for(String section:List.of("observations","children")){
            var seen=new ArrayList<Object>();
            for(int offset=0;offset<3;offset++){
                f.reads.set(0);
                var page=section.equals("observations")?f.service.notes(asset,offset,10):f.service.read(asset,section,root.id(),offset,10);
                var rows=(List<Map<String,Object>>)page.get(section.equals("observations")?section:"topics");
                assertEquals(1,rows.size());seen.add(rows.get(0).get("id"));
                assertEquals(offset+1,page.get("next_offset"));assertEquals(offset==2,page.get("complete"));
                assertTrue(json.valueToTree(rows).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<16000);
                assertTrue(f.reads.get()<=(section.equals("observations")?2:3),"At most one candidate beyond the byte budget is fetched");
            }
            assertEquals(root.members(),seen);
        }
    }
    @Test void searchCanContinuePastFiveThousandAndEmptyPagesDoNotReadClassificationObjects(){
        var execution=new FeedbackPolicy(6000,67108864,24000,100,2000,16000000,Duration.ofHours(1),Duration.ofSeconds(30));
        var f=new Fixture(execution);var job=f.start(5021);assertEquals(Phase.READY,f.finish(job.id()).phase());
        var asset=f.service.asset(job.id(),owner,request);var ids=new ArrayList<Object>();
        for(int offset:List.of(5000,5010,5020)){
            var page=f.service.search(asset,"观众反馈",offset,10);var rows=(List<Map<String,Object>>)page.get("matches");
            rows.forEach(t->{ids.add(t.get("id"));assertNotNull(t.get("topic_id"));});
            assertEquals(5021,page.get("matched_records"));assertEquals(Math.min(5021,offset+10),page.get("next_offset"));
            assertEquals(offset==5020,page.get("complete"));
        }
        assertEquals(21,new HashSet<>(ids).size());assertEquals("D:2511",ids.get(20));
        f.reads.set(0);var empty=f.service.search(asset,"不存在",0,10);
        assertEquals(List.of(),empty.get("matches"));assertEquals(true,empty.get("complete"));assertEquals(asset.snapshot().chunks().size(),f.reads.get());
        assertEquals(true,f.service.search(asset,"观众反馈",100000,10).get("complete"));
        assertThrows(AgentException.class,()->f.service.search(asset,"观众反馈",100001,10));
    }
    @Test void durableProviderReservationsReconcileRunCountsOncePerAttempt(){
        var f=new Fixture();var job=f.start(81);
        var run=new com.tianmu.agent.runtime.RunState(owner,"analysis");
        run.bindControl(Duration.ofMinutes(1),2);
        var notifications=new AtomicInteger();
        for(int i=0;i<20 && MemoryFeedbackJobStore.active(f.store.get(job.id()).phase());i++) {
            f.service.advance(job.id(),run.cancellation(),()->{
                notifications.incrementAndGet();run.backgroundModelCalls(f.store.get(job.id()).modelCalls());
            });
        }
        assertEquals(Phase.READY,f.store.get(job.id()).phase());
        assertEquals(f.calls.get(),notifications.get());
        assertEquals(f.calls.get(),run.snapshot().backgroundModelCalls());
        assertTrue(f.calls.get()>2,"Analysis calls must not consume the foreground call limit");
        run.modelCallStarted();run.modelCallStarted();
        assertEquals(f.calls.get()+2,run.snapshot().totalModelCalls());
    }
    @Test void fiveThousandOriginalsUseOnlyClassificationCallsAndBoundedPriorTopics(){
        var f=new Fixture();var maxGroups=new AtomicInteger();var modelBytes=new AtomicInteger();
        f.inspect=input->{maxGroups.accumulateAndGet(input.groups().size(),Math::max);modelBytes.accumulateAndGet(f.objects.bytes(Map.of("groups",input.groups(),"known_topics",input.knownTopics())),Math::max);};
        var job=f.start(5000);f.service.advance(job.id());f.reads.set(0);
        for(int i=0;i<125;i++)f.service.advance(job.id());
        assertEquals(249,f.reads.get(),"125 batch inputs plus 124 accepted results, not 7750 prefix result reads");
        var finished=f.finish(job.id());assertEquals(Phase.READY,finished.phase(),finished.errorCode());
        var asset=f.service.asset(job.id(),owner,request);assertEquals(5000,asset.snapshot().counts().total());assertEquals(2500,asset.snapshot().counts().comments());
        assertEquals(2500,asset.snapshot().counts().danmaku());assertTrue(asset.snapshot().chunks().size()>1);
        assertEquals(5000,f.service.topics(asset).stream().mapToLong(Topic::messages).sum());assertEquals(40,maxGroups.get());
        assertTrue(modelBytes.get()<30000,"Each model receives a bounded batch, not the original transcript");assertEquals(125,f.calls.get());
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
    @Test void membershipCorrectionIsPersistedWithTheBatchAndNextAttemptRepairsBeforeAcceptance()throws Exception {
        var f=new Fixture();var seen=new AtomicInteger();
        f.proposals=input->{
            var ids=input.groups().stream().map(Group::id).toList();
            if(seen.getAndIncrement()==0){assertNull(input.correction());return List.of(new Proposal("T1","反馈","原有观察","",ids.subList(1,ids.size())));}
            assertEquals(List.of("G1"),input.correction().missing());assertEquals("原有观察",input.correction().previousTopics().get(0).observation());
            assertEquals(input,json.convertValue(json.valueToTree(input),Input.class),"Correction survives private object serialization");
            return List.of(new Proposal("T1","反馈","已补齐归属","",ids));
        };
        var job=f.start(4);f.service.advance(job.id());f.service.advance(job.id());
        assertEquals(0,f.store.get(job.id()).completedWork());assertNull(f.store.get(job.id()).asset());
        Thread.sleep(2100);assertEquals(Phase.READY,f.finish(job.id()).phase());
        assertEquals(2,seen.get());assertEquals(2,f.store.get(job.id()).modelCalls());
        assertEquals(4,f.service.asset(job.id(),owner,request).snapshot().counts().total());
    }
    @Test void staleAttemptCannotReplaceTheReclaimedWorkersInputWithCorrection() {
        var f=new Fixture();var job=f.start(2);f.service.advance(job.id());
        var first=f.store.claim(job.id(),Instant.now().minusSeconds(10),Duration.ofSeconds(1));
        var second=f.store.claim(job.id(),Instant.now(),Duration.ofSeconds(60));
        var replacement=f.objects.put(job.id(),Map.of("correction","late"));
        assertFalse(f.store.retry(first,"INVALID_ANALYSIS",Instant.now(),3,replacement));
        assertEquals(second.input(),f.store.work(second.id()).input());
        assertTrue(f.store.retry(second,"INVALID_ANALYSIS",Instant.now(),3,replacement));
        assertEquals(replacement,f.store.claim(job.id(),Instant.now(),Duration.ofSeconds(60)).input());
    }
    @Test void twoHundredFortyThreeRecordsNeedSevenCallsAndNoModelMerge(){
        var f=new Fixture();var batches=new AtomicInteger();
        f.inspect=input->{
            assertEquals(WorkKind.CLASSIFY,input.kind());
            int before=batches.getAndIncrement()*40;
            assertEquals(before,input.knownTopics().stream().mapToLong(KnownTopic::messages).sum());
            if(before>0)assertEquals("T1",input.knownTopics().get(0).key());
        };
        var job=f.start(243);assertEquals(Phase.READY,f.finish(job.id()).phase());
        assertEquals(7,f.calls.get());var asset=f.service.asset(job.id(),owner,request);
        assertEquals(243,f.service.topics(asset).stream().mapToLong(Topic::messages).sum());
        assertEquals(7,((List<?>)f.service.read(asset,"children",f.service.topics(asset).get(0).id(),0,10).get("topics")).size());
    }
    @Test void retryBackoffCannotLetLaterBatchesSkipTheAcceptedPrefix(){
        var f=new Fixture();var job=f.start(80);f.service.advance(job.id());
        var first=f.store.claim(job.id(),Instant.now(),Duration.ofSeconds(60));
        assertTrue(f.store.retry(first,"INVALID_ANALYSIS",Instant.now().plusSeconds(30),3));
        assertNull(f.store.claim(job.id(),Instant.now(),Duration.ofSeconds(60)));
        assertEquals(0,f.store.completedRange(job.id(),0,0,1).size());
    }
    @Test void rejectedCommitDoesNotPolluteTheNextBatchDirectory(){
        var f=new Fixture();var guarded=org.mockito.Mockito.spy(f.store);var rejected=new AtomicReference<FeedbackJobStore.Work>();
        org.mockito.Mockito.doAnswer(call->{
            FeedbackJobStore.Work claim=call.getArgument(0);
            if(rejected.compareAndSet(null,claim))return false;
            return call.callRealMethod();
        }).when(guarded).complete(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var service=new FeedbackAnalysisService(guarded,f.objects,f.source,f.models,policy());
        f.proposals=input->List.of(new Proposal(f.calls.get()==1?"T99":"T1",f.calls.get()==1?"未提交主题":"已提交主题","观点","",input.groups().stream().map(Group::id).toList()));
        var job=f.start(80);service.advance(job.id());service.advance(job.id());
        assertEquals(0,f.store.get(job.id()).completedWork());
        assertTrue(f.store.retry(rejected.get(),"MODEL_UNAVAILABLE",Instant.MIN,3));
        f.inspect=input->assertTrue(input.knownTopics().isEmpty());service.advance(job.id());
        f.inspect=input->assertEquals(List.of(new KnownTopic("T1","已提交主题",40)),input.knownTopics());service.advance(job.id());
        assertEquals(2,f.store.get(job.id()).completedWork());assertEquals(3,f.calls.get());
    }
    @Test void identicalAuthorizedSnapshotsReuseCompletedClassificationsWithNewRunLocalEvidence(){
        var f=new Fixture();var first=f.start(81);assertEquals(Phase.READY,f.finish(first.id()).phase());assertEquals(3,f.calls.get());
        var second=f.start(81);assertEquals(Phase.READY,f.finish(second.id()).phase());
        assertEquals(3,f.calls.get());assertEquals(0,f.store.get(second.id()).modelCalls());assertEquals(2,f.exports.get());
        var asset=f.service.asset(second.id(),owner,request);assertEquals(second.id(),asset.jobId());
        var topic=f.service.topics(asset).get(0);assertEquals(81,topic.messages());
        var leaves=(List<?>)f.service.read(asset,"children",topic.id(),0,10).get("topics");
        assertEquals(3,leaves.size());String leaf=(String)((Map<?,?>)leaves.get(0)).get("id");
        assertTrue(f.service.read(asset,"originals",leaf,0,1).containsKey("originals"));
        f.authorized.set(false);assertThrows(AgentException.class,()->f.start(81));
    }
    @Test void stableKeysIncludingT100SurviveCodeOnlyAggregationBeyondTenTotalTopics(){
        var f=new Fixture();var batch=new AtomicInteger();
        f.proposals=input->{
            int base=batch.getAndIncrement()==0?90:0;var out=new ArrayList<Proposal>();
            for(int i=0;i<10;i++)out.add(new Proposal("T"+(base+i+1),"主题"+(base+i+1),"保留意见","",
                    input.groups().subList(i*4,i*4+4).stream().map(Group::id).toList()));
            return out;
        };
        var job=f.start(80);assertEquals(Phase.READY,f.finish(job.id()).phase());assertEquals(2,f.calls.get());
        var asset=f.service.asset(job.id(),owner,request);assertEquals(20,f.service.topics(asset).size());
        assertTrue(f.service.topics(asset).stream().anyMatch(t->t.id().endsWith(":T100")));
        var first=f.service.read(asset,"topics",0,10);assertEquals(false,first.get("complete"));
        assertEquals(true,f.service.read(asset,"topics",10,10).get("complete"));
    }
    @Test void earlyMinorityAtTheEndOfALongObservationSurvivesLaterBatchesAndWorkerRestart(){
        var f=new Fixture();var batch=new AtomicInteger();
        String early="前批观察。".repeat(170)+"少数观众认为字幕大小刚好，不希望统一放大。";
        String advice="先在手机端验证显示效果。".repeat(38)+"保留字幕大小选项，不直接覆盖现有设置。";
        f.proposals=input->{int index=batch.getAndIncrement();
            return List.of(new Proposal("T1",index==0?"字幕可读性":"字幕太小",index==0?early:"当前批次希望放大字幕。",
                    index==0?advice:"可测试较大字幕。",input.groups().stream().map(Group::id).toList()));};
        var job=f.start(81);f.service.advance(job.id());f.service.advance(job.id());
        var recovered=new FeedbackAnalysisService(f.store,f.objects,f.source,f.models,policy());
        for(int i=0;i<20 && MemoryFeedbackJobStore.active(f.store.get(job.id()).phase());i++)recovered.advance(job.id());
        assertEquals(Phase.READY,f.store.get(job.id()).phase());assertEquals(3,f.calls.get());
        var asset=recovered.asset(job.id(),owner,request);var top=recovered.topics(asset).get(0);
        assertEquals("字幕可读性",top.title());assertTrue(top.observation().contains(early));
        assertTrue(top.observation().contains("当前批次希望放大字幕"));assertTrue(top.recommendation().contains("保留字幕大小选项"));
        String html=new String(recovered.report(asset),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("少数观众认为字幕大小刚好"));assertTrue(html.contains("保留字幕大小选项"));
        var leaves=(List<?>)recovered.read(asset,"children",top.id(),0,10).get("topics");
        assertEquals(early,((Map<?,?>)leaves.get(0)).get("observation"));
    }
    @Test void overflowNeverCutsAnOpinionAndTheReportRetainsMoreThanSixtyBatchNotes(){
        var f=new Fixture();var batch=new AtomicInteger();
        f.proposals=input->{int index=batch.incrementAndGet();return List.of(new Proposal("T1","字幕反馈",
                "第"+index+"批观点："+"需要保留语境。".repeat(120)+"这批的少数意见标记"+index,"",
                input.groups().stream().map(Group::id).toList()));};
        var job=f.start(2440);assertEquals(Phase.READY,f.finish(job.id()).phase());assertEquals(61,f.calls.get());
        var asset=f.service.asset(job.id(),owner,request);var top=f.service.topics(asset).get(0);
        assertTrue(top.observation().contains("超过摘要展示长度"));assertFalse(top.observation().contains("第61批观点"));
        String html=new String(f.service.report(asset),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(61,html.split("<details>",-1).length-1);
        for(int i=1;i<=61;i++)assertTrue(html.contains("这批的少数意见标记"+i));
        int next=0,total=0;boolean complete=false;
        while(!complete){var page=f.service.read(asset,"children",top.id(),next,10);total+=((List<?>)page.get("topics")).size();
            next=((Number)page.get("next_offset")).intValue();complete=Boolean.TRUE.equals(page.get("complete"));}
        assertEquals(61,total);
    }
    @Test void literalSearchReturnsOnlyHitsAndOriginalReferencesWithoutCallingModelsAgain(){
        var f=new Fixture();var job=f.start(100);assertEquals(Phase.READY,f.finish(job.id()).phase());int calls=f.calls.get();
        var asset=f.service.asset(job.id(),owner,request);var first=f.service.search(asset,"反馈1",0,3);
        assertEquals("MATCHED_RECORDS_ONLY",first.get("coverage"));assertEquals(12,((Number)first.get("matched_records")).intValue());
        assertEquals("PARENT_CHAIN_ONLY",first.get("reply_context_scope"));assertEquals("NOT_QUERIED",first.get("child_reply_presence"));
        assertEquals(3,((List<?>)first.get("matches")).size());assertEquals(false,first.get("complete"));
        var hit=(Map<?,?>)((List<?>)first.get("matches")).get(0);assertNotNull(hit.get("topic_id"));
        assertTrue(hit.get("match_excerpt").toString().contains("反馈1"));
        assertTrue(f.service.originalText(asset,hit.get("topic_id").toString(),hit.get("id").toString(),0,2048).containsKey("page"));
        assertEquals(0,((Number)f.service.search(asset,"不存在的关键字",0,10).get("matched_records")).intValue());
        assertEquals(calls,f.calls.get());
    }
    @Test void oversizedSummaryTextProducesActionableCorrectionInsteadOfAnIdenticalBlindRetry() {
        var f=new Fixture();var planner=new FeedbackPlanner(f.objects,policy());
        var input=new Input(WorkKind.CLASSIFY,List.of(new Group("G1",Source.COMMENT,"原文","",false,null,1,List.of("C:1"))),List.of(),null);
        for(var p:List.of(new Proposal("T1","x".repeat(101),"观察","",List.of("G1")),
                new Proposal("T1","主题","x".repeat(2401),"",List.of("G1")),new Proposal("T1","主题","观察","x".repeat(1363),List.of("G1"))))
            assertEquals("TEXT_LIMIT_EXCEEDED",assertThrows(FeedbackCorrectionException.class,()->planner.validate("work",input,List.of(p))).correction().issue());
    }
    @Test void largerBoundedBatchesPreserveEveryOriginalAndAuthorCount(){
        var f=new Fixture(new FeedbackPolicy(5000,67108864,24000,80,2000,16000000,Duration.ofHours(1),Duration.ofSeconds(30)));
        var job=f.start(243);assertEquals(Phase.READY,f.finish(job.id()).phase());assertEquals(4,f.calls.get());
        var asset=f.service.asset(job.id(),owner,request);assertEquals(243,asset.snapshot().counts().total());
        assertEquals(243,f.service.topics(asset).stream().mapToLong(Topic::messages).sum());
        assertEquals(243,((Number)f.service.search(asset,"观众反馈",0,10).get("matched_records")).intValue());
    }
}
