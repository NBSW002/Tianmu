package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackPreparationRecoveryTest {
    @TempDir Path directory;
    final MemoryFeedbackJobStore jobs=new MemoryFeedbackJobStore();
    final MemoryFeedbackPreparationStore work=new MemoryFeedbackPreparationStore();
    final AccessScope owner=new AccessScope("t","u",Set.of(1001L));
    final Request request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),Set.of(Source.COMMENT,Source.DANMAKU),Grounding.VIDEO_GROUNDED,0L,10000L,FeedbackPreparationService.VERSION);
    final VideoContent.Facts video=new VideoContent.Facts("video-content-v1",1001,"v1",0,10000,"采样观察",List.of(new VideoContent.Moment(1000,"画面有商品")),"","SAMPLED_FRAMES",List.of(1000L),"fixture",Instant.now());
    final AtomicInteger exports=new AtomicInteger();final AtomicBoolean authorized=new AtomicBoolean(true);final List<Input> inputs=new ArrayList<>();
    final FeedbackSnapshotPort source=new FeedbackSnapshotPort(){
        public void authorize(AccessScope caller,long id){if(!authorized.get())throw new AgentException("AUTH_CONTEXT_EXPIRED","fixture");}
        public Receipt export(Request r,AccessScope caller,CallControl control,java.util.function.Consumer<List<Unit>> sink){
            exports.incrementAndGet();var rows=new ArrayList<Unit>();
            for(var s:r.sources())for(int i=1;i<=2;i++)rows.add(new Unit((s==Source.COMMENT?"C:":"D:")+i,s,""+i,"短句"+i,"",false,s==Source.DANMAKU?1000L:null,"viewer"+i,Instant.parse("2026-11-01T01:00:00Z")));
            sink.accept(rows);return new Receipt(Instant.now(),new Counts(2,2,0,0,2));
        }
    };
    final FeedbackModels models=new FeedbackModels(){
        public String modelName(){return "fixture";}public String policyHash(){return "fixture-policy";}
        public List<Proposal> analyze(Input input,CallControl control){
            inputs.add(input);ModelBudget.execute(control,"fixture",true,8192,()->new ObjectMapper().createObjectNode().set("usage",new ObjectMapper().createObjectNode().put("prompt_tokens",3).put("completion_tokens",2)));
            return List.of(new Proposal("T1","综合观察","保留正反观点","",input.kind()==WorkKind.CLASSIFY?input.groups().stream().map(Group::id).toList():input.children().stream().map(Topic::id).toList()));
        }
    };
    FeedbackObjects objects(){return new FeedbackObjects(new FileObjectStore(directory),new ObjectMapper().findAndRegisterModules());}
    FeedbackAnalysisService analysis(){return new FeedbackAnalysisService(jobs,objects(),source,models,FeedbackPolicy.defaults());}
    FeedbackPreparationService preparation(FeedbackAnalysisService analysis){return new FeedbackPreparationService(analysis,work,objects(),FeedbackPolicy.defaults(),new FeedbackClusters(null,null));}
    CallControl control(){return new CallControl(Instant.now().plusSeconds(30),new CancellationToken());}

    @Test void restartKeepsCompletedSourceAndSharedBudgetAndOnlyDanmakuReceivesVideo(){
        UUID run=UUID.randomUUID();var analysis=analysis();var first=preparation(analysis);
        var job=first.request(run,owner,request,video,control().deadline(),Source.COMMENT);
        first.advance(job.id(),FeedbackPreparationStore.Step.SNAPSHOT,new CancellationToken());first.advance(job.id(),FeedbackPreparationStore.Step.COMMENT,new CancellationToken());
        assertTrue(first.ready(job.id(),Source.COMMENT));assertTrue(inputs.isEmpty());
        var restartedAnalysis=analysis();var restarted=preparation(restartedAnalysis);
        assertEquals(job.id(),restarted.request(run,owner,request,video,job.deadline(),Source.DANMAKU).id());
        assertTrue(restarted.ready(job.id(),Source.COMMENT));restarted.advance(job.id(),FeedbackPreparationStore.Step.DANMAKU,new CancellationToken());
        restarted.startReport(job.id(),owner,control());
        for(int i=0;i<20 && jobs.get(job.id()).phase()!=Phase.READY;i++)restartedAnalysis.advance(job.id());
        assertEquals(Phase.READY,jobs.get(job.id()).phase());assertEquals(1,exports.get());assertEquals(3,jobs.get(job.id()).modelCalls());
        assertEquals(15,jobs.get(job.id()).chargedTokens());assertEquals(2,jobs.get(job.id()).snapshot().counts().uniqueAudience());
        for(var input:inputs)if(input.kind()==WorkKind.CLASSIFY){
            assertTrue(input.knownTopics().isEmpty());assertNotNull(input.groups().get(0).candidateCluster());
            if(input.groups().get(0).source()==Source.COMMENT)assertNull(input.video());else assertEquals(video,input.video());
        }
        var asset=restartedAnalysis.asset(job.id(),owner,request);assertEquals(4,restartedAnalysis.topics(asset).stream().mapToLong(Topic::messages).sum());
        assertTrue(new String(restartedAnalysis.report(asset),java.nio.charset.StandardCharsets.UTF_8).contains("短句"));
    }
    @Test void expiredWorkerCannotOverwriteAcceptedChildAndBudgetSurvivesRetries()throws Exception {
        UUID id=UUID.randomUUID();work.request(id,FeedbackPreparationStore.Step.SNAPSHOT);
        var old=work.claim(id,FeedbackPreparationStore.Step.SNAPSHOT,Instant.now().minusSeconds(2),Duration.ofSeconds(1));
        var current=work.claim(id,FeedbackPreparationStore.Step.SNAPSHOT,Instant.now(),Duration.ofSeconds(30));
        var blob=objects().put(id,Map.of("fixture",true));
        assertFalse(work.complete(old,blob));assertTrue(work.complete(current,blob));assertNull(work.claim(id,FeedbackPreparationStore.Step.SNAPSHOT,Instant.now(),Duration.ofSeconds(30)));
        work.reserveEmbedding(id,1);assertEquals("EMBEDDING_BUDGET_EXCEEDED",assertThrows(AgentException.class,()->work.reserveEmbedding(id,1)).code());
    }
    @Test void revokedAuthorizationPreventsSourcePublication(){
        var service=analysis();var prep=preparation(service);var job=prep.request(UUID.randomUUID(),owner,request,video,control().deadline(),Source.COMMENT);
        prep.advance(job.id(),FeedbackPreparationStore.Step.SNAPSHOT,new CancellationToken());authorized.set(false);
        prep.advance(job.id(),FeedbackPreparationStore.Step.COMMENT,new CancellationToken());
        assertFalse(prep.ready(job.id(),Source.COMMENT));assertEquals(Phase.WAITING_AUTH,jobs.get(job.id()).phase());assertTrue(inputs.isEmpty());
        authorized.set(true);service.resume(job.id(),owner);prep.advance(job.id(),FeedbackPreparationStore.Step.COMMENT,new CancellationToken());assertTrue(prep.ready(job.id(),Source.COMMENT));assertEquals(1,exports.get());
    }
}
