package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.store.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportProgressServiceTest {
    final UUID runId = UUID.randomUUID();
    final AccessScope owner = new AccessScope("tenant", "881", Set.of(1001L, 1002L));
    final String input = "ev_input";
    final InMemoryEvidenceStore store = new InMemoryEvidenceStore();

    record Context(UUID runId, AccessScope scope, EvidenceStore evidenceStore) implements EvidenceContext {
        public LocalDate businessDate() { return LocalDate.now(DanmakuTypes.ZONE); }
        public TaskTypes.PreparedTask task() { return null; }
        public void checkpoint() {}
    }
    ReportProgressService service(EvidenceStore evidence, ExecutionState execution) {
        return new ReportProgressService(new Context(runId, owner, evidence), execution);
    }
    ReportProgressService fresh() { return service(store, new ExecutionState(store)); }
    void input() {
        var end = LocalDateTime.now(DanmakuTypes.ZONE).minusMinutes(1).withNano(0);
        var q = new DanmakuTypes.Query(1001, end.minusDays(7), end);
        var groups = List.of(new TextGroup("1", "问题一", 2, 500L, false),
                new TextGroup("2", "问题二", 1, 500L, false), new TextGroup("3", "问题三", 3, 500L, false));
        var bins = new ArrayList<DanmakuTypes.Bucket>();
        for (int i=0; i<12; i++) bins.add(new DanmakuTypes.Bucket(i,i==0?6:0));
        var data = new DanmakuTypes.Dataset(q,6,2,bins,List.of(),Instant.now(),"fixture",false,
                new Overview(List.of(),List.of(new Position(0,1000,6)),0,groups,6,6));
        store.put(new Evidence(input,runId,owner,null,data));
    }
    Topic topic(String key, List<String> ids, long messages) {
        return new Topic(key,"讨论","观众原话","",ids,messages,"",List.of());
    }
    DanmakuClassification progress(int offset, boolean merged) {
        return new DanmakuClassification(offset,List.of(topic("T1",List.of("1","2","3").subList(0,offset),offset==1?2:offset==2?3:6)),null,merged);
    }
    void checkpoint(UUID run, AccessScope scope, DanmakuClassification progress) {
        store.put(new Evidence("ev_"+UUID.randomUUID(),run,scope,null,null,null,null,
                new ReportCheckpoint("audience-report-checkpoint-v1",input,progress)));
    }
    long checkpoints() { return store.snapshot().values().stream().filter(e->e.reportCheckpoint()!=null).count(); }

    @Test void restoresMostAdvancedOwnedCheckpointAndPrefersCompletedAtTheSameOffset() {
        input();checkpoint(runId,owner,progress(1,false));checkpoint(runId,owner,progress(3,false));checkpoint(runId,owner,progress(3,true));
        checkpoint(UUID.randomUUID(),owner,new DanmakuClassification(99,List.of()));
        checkpoint(runId,new AccessScope("tenant","882",owner.videoIds()),new DanmakuClassification(99,List.of()));
        assertEquals(progress(3,true),fresh().load(input));
        assertEquals(5,checkpoints(),"Reading recovery must not write another checkpoint");
    }
    @Test void corruptLatestCheckpointFailsWithoutFallingBackToAnOlderPrefix() {
        input();checkpoint(runId,owner,progress(1,false));checkpoint(runId,owner,new DanmakuClassification(4,List.of()));
        var state=new ExecutionState(store);
        assertEquals("INVALID_REPORT",assertThrows(AgentException.class,()->service(store,state).load(input)).code());
        assertFalse(state.hasClassification(input));
    }
    @Test void rejectsMissingRepeatedUnknownMembersWrongCountsAndPrematureCompletionBeforeWriting() {
        input();var state=new ExecutionState(store);var service=service(store,state);
        var invalid=List.of(
                new DanmakuClassification(2,List.of(topic("T1",List.of("1"),2))),
                new DanmakuClassification(1,List.of(topic("T1",List.of("1","1"),4))),
                new DanmakuClassification(1,List.of(topic("T1",List.of("2"),1))),
                new DanmakuClassification(1,List.of(topic("T1",List.of("1"),1))),
                new DanmakuClassification(2,List.of(topic("T1",List.of("1"),2),topic("T1",List.of("2"),1))),
                progress(1,true));
        for(var candidate:invalid)assertEquals("INVALID_REPORT",assertThrows(AgentException.class,()->service.save(input,candidate)).code());
        assertEquals(0,checkpoints());assertEquals(0,state.classification(input).offset());
    }
    @Test void failedPrivateWriteCannotAdvanceMemoryAndRetryCanRecoverFromTheStore() {
        input();
        var failing=new EvidenceStore() {
            boolean fail=true;
            public Evidence get(String id){return store.get(id);}
            public Map<String,Evidence> snapshot(){return store.snapshot();}
            public void put(Evidence value){if(fail){fail=false;throw new AgentException("STORAGE_UNAVAILABLE","fixture");}store.put(value);}
        };
        var state=new ExecutionState(failing);var service=service(failing,state);
        assertEquals("STORAGE_UNAVAILABLE",assertThrows(AgentException.class,()->service.save(input,progress(1,false))).code());
        assertEquals(0,state.classification(input).offset());assertEquals(0,checkpoints());
        service.save(input,progress(1,false));assertEquals(progress(1,false),fresh().load(input));
    }
    @Test void cannotRollBackProgressOrRepeatIdenticalPrivateWrites() {
        input();var service=fresh();service.save(input,progress(2,false));
        assertEquals("INVALID_REPORT",assertThrows(AgentException.class,()->service.save(input,progress(1,false))).code());
        service.save(input,progress(3,true));long writes=checkpoints();service.save(input,progress(3,true));
        assertEquals(writes,checkpoints());
        assertEquals("INVALID_REPORT",assertThrows(AgentException.class,()->service.save(input,progress(3,false))).code());
    }
    @Test void conflictingCheckpointsCannotChooseAnArbitraryDraftAtTheSamePosition() {
        input();checkpoint(runId,owner,progress(1,false));
        var other=new DanmakuClassification(1,List.of(topic("T2",List.of("1"),2)));
        assertEquals("INVALID_REPORT",assertThrows(AgentException.class,()->fresh().save(input,other)).code());
        checkpoint(runId,owner,other);
        assertEquals("INVALID_REPORT",assertThrows(AgentException.class,()->fresh().load(input)).code());
    }
    @Test void fullSnapshotCacheCanAdvanceAnOwnedDraftWithoutReclassifyingAcceptedRecords() {
        input();var cache=new ReportAnalysisCache(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),"fixture");
        var data=store.get(input).analysis();cache.put(owner,data,null,progress(3,true));
        var service=fresh();service.save(input,progress(1,false));
        var reused=cache.get(owner,data,null);assertNotNull(reused);
        service.save(input,reused);assertEquals(progress(3,true),fresh().load(input));
        assertEquals(2,checkpoints());
    }
    @Test void foreignInputsAndForeignVideoReferencesAreRejectedEvenWithValidMembership() {
        input();
        store.put(new Evidence("ev_foreign",UUID.randomUUID(),owner,null,store.get(input).analysis()));
        assertEquals("RESULT_NOT_FOUND",assertThrows(AgentException.class,()->fresh().load("ev_foreign")).code());
        var video=new VideoContent.Facts("video-content-v1",1001,"fixture",0,1000,"场景",List.of(new VideoContent.Moment(500,"画面")),"","SAMPLED_FRAMES",List.of(500L),"fixture",Instant.now());
        store.put(new Evidence("ev_video",UUID.randomUUID(),owner,null,null,video));
        var invalid=new DanmakuClassification(1,progress(1,false).topics(),"ev_video");
        assertEquals("RESULT_NOT_FOUND",assertThrows(AgentException.class,()->fresh().save(input,invalid)).code());
        assertEquals(0,checkpoints());
    }
}
