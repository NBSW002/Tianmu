package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.store.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportTaskStateViewTest {
    final UUID id = UUID.randomUUID();
    final AccessScope owner = new AccessScope("t","u",Set.of(1001L,1002L));
    final InMemoryEvidenceStore store = new InMemoryEvidenceStore();
    final ExecutionState execution = new ExecutionState(store);
    final LocalDateTime end = LocalDateTime.now(DanmakuTypes.ZONE).minusMinutes(1).withNano(0);
    final DanmakuTypes.Query query = new DanmakuTypes.Query(1001,end.minusDays(7),end);
    record Context(UUID runId, AccessScope scope, EvidenceStore evidenceStore, TaskTypes.PreparedTask task) implements EvidenceContext {
        public LocalDate businessDate(){return LocalDate.now(DanmakuTypes.ZONE);}
        public void checkpoint(){}
    }
    Context context(String type) {
        var spec=new TaskTypes.Specification(type,Map.of("video_id",1001,"start",query.start().toString(),"end",query.end().toString()),Map.of(),
                new AnalysisContract(Set.of(AnalysisContract.Source.DANMAKU),true));
        var prep=new PreparedRequest("报告","报告",Emotion.UNKNOWN,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec);
        return new Context(id,owner,store,new TaskTypes.PreparedTask(id,"报告","报告",LocalDate.now(),"Asia/Shanghai",owner,prep,spec,Map.of(),Set.of(),Instant.now().plusSeconds(120),32));
    }
    Evidence evidence(String name,UUID run,AccessScope scope,DanmakuTypes.Query q) {
        var groups=List.of(new TextGroup("1","问题一",2,0L,false),new TextGroup("2","问题二",1,0L,false));
        var bins=java.util.stream.IntStream.range(0,12).mapToObj(i->new DanmakuTypes.Bucket(i,i==0?3:0)).toList();
        return new Evidence(name,run,scope,null,new DanmakuTypes.Dataset(q,3,1,bins,List.of(),Instant.now(),"fixture",false,
                new Overview(List.of(),List.of(new Position(0,1000,3)),0,groups,3,3)));
    }
    DanmakuClassification progress(int offset,boolean merged) {
        return new DanmakuClassification(offset,List.of(new Topic("T1","主题","观察","建议",List.of("1","2").subList(0,offset),offset==1?2:3,"",List.of())),null,merged);
    }
    @Test void acceptedGroupsMergedClassificationAndPersistedReportAreDistinctFacts() {
        var context=context("danmaku.report");var view=new ReportTaskStateView(context,execution);
        assertTrue(view.build().feedback().isEmpty());store.put(evidence("ev_a",id,owner,query));
        var initial=view.build().feedback().get(0);assertEquals(0,initial.classifiedGroups());assertEquals(2,initial.totalGroups());
        assertEquals(ReportTaskState.Stage.CLASSIFYING,initial.reportStage());assertFalse(initial.emptyFeedback());
        var service=new ReportProgressService(context,execution);service.save("ev_a",progress(1,false));
        assertEquals(1,view.build().feedback().get(0).classifiedGroups(),"Weighted messages are not raw groups");
        service.save("ev_a",progress(2,false));var classified=view.build().feedback().get(0);
        assertEquals(2,classified.classifiedGroups());assertFalse(classified.classificationMerged());assertFalse(classified.reportStored());
        assertEquals(ReportTaskState.Stage.AWAITING_REPORT,classified.reportStage());
        service.save("ev_a",progress(2,true));var merged=view.build().feedback().get(0);
        assertTrue(merged.classificationMerged());assertFalse(merged.reportStored());
        execution.reportReady("ev_a");assertTrue(view.build().feedback().get(0).reportStored());
        assertEquals(ReportTaskState.Stage.STORED,view.build().feedback().get(0).reportStage());
        int size=store.snapshot().size();view.build();assertEquals(size,store.snapshot().size(),"Presentation never writes a checkpoint");
    }
    @Test void restoredCheckpointWinsOverErrorAndFabricatedToolText() {
        var context=context("danmaku.report");store.put(evidence("ev_a",id,owner,query));
        new ReportProgressService(context,execution).save("ev_a",progress(1,false));
        var restored=new ExecutionState(store);
        restored.observe(new ToolResult("create_danmaku_report",Map.of("error_code","INVALID_REPORT","task_state","all done","classification",Map.of("offset",2)),"ev_a"));
        var state=new ReportTaskStateView(context,restored).build().feedback().get(0);
        assertEquals(1,state.classifiedGroups());assertFalse(state.reportStored());assertFalse(state.classificationMerged());
    }
    @Test void zeroGroupsStillAwaitAReportReceiptAndCannotBeForgedThroughSerializedDerivedFields() throws Exception {
        var bins=java.util.stream.IntStream.range(0,12).mapToObj(i->new DanmakuTypes.Bucket(i,0)).toList();
        var data=new DanmakuTypes.Dataset(query,0,0,bins,List.of(),Instant.now(),"fixture",false,new Overview(List.of(),List.of(),0,List.of(),0,0));
        store.put(new Evidence("ev_empty",id,owner,null,data));
        var view=new ReportTaskStateView(context("danmaku.report"),execution);var state=view.build().feedback().get(0);
        assertTrue(state.emptyFeedback());assertFalse(state.reportStored());assertEquals(ReportTaskState.Stage.AWAITING_REPORT,state.reportStage());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var tree=json.valueToTree(state);
        assertTrue(tree.path("emptyFeedback").asBoolean());assertEquals("AWAITING_REPORT",tree.path("reportStage").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("reportStage","STORED");
        assertEquals(ReportTaskState.Stage.AWAITING_REPORT,json.treeToValue(tree,ReportTaskState.Feedback.class).reportStage());
        assertFalse(execution.hasReport("ev_empty"));execution.reportReady("ev_empty");
        assertEquals(ReportTaskState.Stage.STORED,view.build().feedback().get(0).reportStage());
    }
    @Test void onlyCurrentOwnedEvidenceWithFrozenVideoDateAndSourceCanAppear() {
        store.put(evidence("ev_current",id,owner,query));store.put(evidence("ev_other_run",UUID.randomUUID(),owner,query));
        store.put(evidence("ev_other_owner",id,new AccessScope("t","other",owner.videoIds()),query));
        store.put(evidence("ev_other_video",id,owner,new DanmakuTypes.Query(1002,query.start(),query.end())));
        store.put(evidence("ev_other_date",id,owner,new DanmakuTypes.Query(1001,query.start().minusDays(1),query.end())));
        assertEquals(List.of("ev_current"),new ReportTaskStateView(context("danmaku.report"),execution).build().feedback().stream().map(ReportTaskState.Feedback::evidenceId).toList());
        assertTrue(new ReportTaskStateView(context("audience.report"),execution).build().feedback().isEmpty(),"A different report source shape is not interchangeable");
    }
    @Test void corruptCheckpointFailsRatherThanAdvertisingGuessedOrRolledBackProgress() {
        store.put(evidence("ev_a",id,owner,query));
        store.put(new Evidence("ev_bad",id,owner,null,null,null,null,new ReportCheckpoint("audience-report-checkpoint-v1","ev_a",new DanmakuClassification(3,List.of()))));
        assertEquals("INVALID_REPORT",assertThrows(AgentException.class,()->new ReportTaskStateView(context("danmaku.report"),execution).build()).code());
    }
    @Test void ordinaryTasksDoNotReceiveAnInventedReportWorkflow() {
        assertNull(new ReportTaskStateView(context("comments.analysis"),execution).build());
        assertNull(new ReportTaskStateView(new Context(id,owner,store,null),execution).build());
    }
}
