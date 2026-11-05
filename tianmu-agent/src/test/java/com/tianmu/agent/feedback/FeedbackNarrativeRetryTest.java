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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackNarrativeRetryTest {
    @Test void badProseIsNeverPublishedAndCorrectionKeepsAllMembers(@TempDir Path directory)throws Exception {
        var calls=new AtomicInteger();var json=new ObjectMapper().findAndRegisterModules();
        var jobs=new MemoryFeedbackJobStore();var objects=new FeedbackObjects(new FileObjectStore(directory),json);
        var source=new FeedbackSnapshotPort(){
            public void authorize(AccessScope caller,long video){}
            public Receipt export(Request request,AccessScope caller,CallControl control,java.util.function.Consumer<List<Unit>> sink){
                var at=Instant.parse("2026-11-01T01:00:00Z");
                sink.accept(List.of(new Unit("C:1",Source.COMMENT,"1","我补充背景说明","",true,null,"author",at),
                        new Unit("C:2",Source.COMMENT,"2","不加配乐更自然","",false,null,"viewer1",at),
                        new Unit("C:3",Source.COMMENT,"3","轻一点的配乐更好","",false,null,"viewer2",at)));
                return new Receipt(Instant.now(),new Counts(3,0,1,0,2));
            }
        };
        var model=new FeedbackModels(){
            public String modelName(){return "fixture";}public String policyHash(){return "fixture-policy";}
            public List<Proposal> analyze(Input input,CallControl control){
                int call=calls.incrementAndGet();
                if(call==2)assertEquals("NARRATIVE_DATASET_COUNTS",input.correction().issue());
                return List.of(new Proposal("T1","背景说明与配乐分歧",call==1?"作者两条发言，G1说明背景。":"作者补充了背景，观众对原声与轻配乐意见不同。",
                        "可分别对比原声版与轻配乐版。",input.kind()==WorkKind.CLASSIFY?input.groups().stream().map(Group::id).toList():input.children().stream().map(Topic::id).toList()));
            }
        };
        var policy=FeedbackPolicy.defaults();var service=new FeedbackAnalysisService(jobs,objects,source,model,policy);
        var prep=new FeedbackPreparationService(service,new MemoryFeedbackPreparationStore(),objects,policy,new FeedbackClusters(null,null));
        var owner=new AccessScope("t","u",Set.of(1001L));var deadline=Instant.now().plusSeconds(20);
        var request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,FeedbackPreparationService.VERSION);
        var job=prep.request(UUID.randomUUID(),owner,request,null,deadline,Source.COMMENT);var token=new CancellationToken();
        prep.advance(job.id(),FeedbackPreparationStore.Step.SNAPSHOT,token);prep.advance(job.id(),FeedbackPreparationStore.Step.COMMENT,token);
        prep.startReport(job.id(),owner,new CallControl(deadline,token));service.advance(job.id());
        assertEquals(0,jobs.get(job.id()).completedWork());assertNotEquals(Phase.READY,jobs.get(job.id()).phase());
        while(jobs.get(job.id()).phase()!=Phase.READY && Instant.now().isBefore(deadline)){service.advance(job.id());Thread.sleep(25);}
        assertEquals(Phase.READY,jobs.get(job.id()).phase());assertEquals(3,calls.get());
        var asset=service.asset(job.id(),owner,request);var root=service.topics(asset).get(0);
        assertEquals(3,root.messages());assertEquals(1,root.authorMessages());
        String html=new String(service.report(asset),java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(html.contains("作者两条"));assertFalse(html.contains("G1"));assertTrue(html.contains("原声"));assertTrue(html.contains("轻配乐"));
    }
}
