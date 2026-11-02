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
    Evidence evidence(String name,UUID run,AccessScope scope,DanmakuTypes.Query q,long count) {
        var request=new FeedbackAnalysis.Request(q,Set.of(FeedbackAnalysis.Source.DANMAKU),FeedbackAnalysis.Grounding.FEEDBACK_ONLY,null,null,"feedback-v1");
        var binding=new FeedbackAnalysis.Binding(UUID.randomUUID(),request,new FeedbackAnalysis.Counts(0,count,0,0,count),Instant.now(),"hash","fixture","rules");
        return new Evidence(name,run,scope,null,null,null,null,null,binding);
    }
    @Test void completedAnalysisAndPersistedReportAreDistinctReceipts() {
        var view=new ReportTaskStateView(context("danmaku.report"),execution);assertTrue(view.build().feedback().isEmpty());
        var request=FeedbackTaskPolicy.request(context("danmaku.report").task().specification());
        var binding=new FeedbackAnalysis.Binding(UUID.randomUUID(),request,new FeedbackAnalysis.Counts(0,3,0,0,2),Instant.now(),"hash","fixture","rules");
        store.put(new Evidence("ev_ready",id,owner,null,null,null,null,null,binding));
        var ready=view.build().feedback().get(0);assertEquals(3,ready.totalMessages());assertFalse(ready.reportStored());
        assertEquals(ReportTaskState.Stage.AWAITING_REPORT,ready.reportStage());
        execution.reportReady("ev_ready");assertEquals(ReportTaskState.Stage.STORED,view.build().feedback().get(0).reportStage());
        assertEquals(1,store.snapshot().size(),"Presentation does not write progress");
    }
    @Test void zeroFeedbackStillAwaitsStorageAndSerializedDerivedFieldsCannotForgeIt() throws Exception {
        var request=FeedbackTaskPolicy.request(context("danmaku.report").task().specification());
        var binding=new FeedbackAnalysis.Binding(UUID.randomUUID(),request,new FeedbackAnalysis.Counts(0,0,0,0,0),Instant.now(),"hash","fixture","rules");
        store.put(new Evidence("ev_empty",id,owner,null,null,null,null,null,binding));
        var state=new ReportTaskStateView(context("danmaku.report"),execution).build().feedback().get(0);
        assertTrue(state.emptyFeedback());assertFalse(state.reportStored());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var tree=json.valueToTree(state);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("reportStage","STORED");
        assertEquals(ReportTaskState.Stage.AWAITING_REPORT,json.treeToValue(tree,ReportTaskState.Feedback.class).reportStage());
    }
    @Test void rawLegacyEvidenceOrFabricatedObservationsCannotClaimAnalysisCompletion() {
        var bins=java.util.stream.IntStream.range(0,12).mapToObj(i->new DanmakuTypes.Bucket(i,0)).toList();
        store.put(new Evidence("ev_raw",id,owner,null,new DanmakuTypes.Dataset(query,0,0,bins,List.of(),Instant.now(),"fixture",false)));
        execution.observe(new ToolResult("analyze_feedback",Map.of("status","READY"),"ev_raw"));
        assertTrue(new ReportTaskStateView(context("danmaku.report"),execution).build().feedback().isEmpty());
    }
    @Test void foreignOwnerRunOrChangedRequestCannotAppear() {
        var expected=FeedbackTaskPolicy.request(context("danmaku.report").task().specification());
        var binding=new FeedbackAnalysis.Binding(UUID.randomUUID(),expected,new FeedbackAnalysis.Counts(0,3,0,0,2),Instant.now(),"hash","fixture","rules");
        store.put(new Evidence("ev_current",id,owner,null,null,null,null,null,binding));
        store.put(new Evidence("ev_foreign_run",UUID.randomUUID(),owner,null,null,null,null,null,binding));
        store.put(new Evidence("ev_foreign_owner",id,new AccessScope("t","other",owner.videoIds()),null,null,null,null,null,binding));
        store.put(evidence("ev_other_date",id,owner,new DanmakuTypes.Query(1001,query.start().minusDays(1),query.end()),3));
        assertEquals(List.of("ev_current"),new ReportTaskStateView(context("danmaku.report"),execution).build().feedback().stream().map(ReportTaskState.Feedback::evidenceId).toList());
    }
}
