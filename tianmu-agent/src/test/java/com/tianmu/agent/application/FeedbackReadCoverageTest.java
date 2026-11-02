package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.preparation.TaskMeaning;
import com.tianmu.agent.runtime.ExecutionState;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackReadCoverageTest {
    final TaskTypes.Specification spec=TaskMeaning.defaults().resolve("分析视频1001的评论，不需要报告，也不读取视频内容",LocalDate.now());
    final Evidence evidence=new Evidence("ev_feedback",UUID.randomUUID(),new AccessScope("demo","alice",Set.of(1001L)),null,null,null,null,null,
            new FeedbackAnalysis.Binding(UUID.randomUUID(),FeedbackTaskPolicy.request(spec),new FeedbackAnalysis.Counts(2,0,0,0,2),Instant.now(),"snapshot","model","policy"));
    ToolExchange page(String evidenceId,String section,int offset,int size,boolean complete){
        return new ToolExchange(new ToolCall("read_result",Map.of("result_id",evidenceId,"section",section,"offset",offset)),
                new ToolResult("read_result",Map.of("topics",Collections.nCopies(size,Map.of("title","topic")),"next_offset",offset+size,"complete",complete),evidenceId));
    }
    void accepted(List<ToolExchange> exchanges){assertDoesNotThrow(()->AnalysisExecutionPolicy.requireTopicsRead(spec,List.of(evidence),exchanges));}
    void rejected(List<ToolExchange> exchanges){assertEquals("INVALID_EVIDENCE",assertThrows(AgentException.class,
            ()->AnalysisExecutionPolicy.requireTopicsRead(spec,List.of(evidence),exchanges)).code());}
    @Test void requiresContiguousPagesForTheCitedEvidence(){
        var first=page(evidence.id(),"topics",0,1,false);var last=page(evidence.id(),"topics",1,1,true);
        rejected(List.of());rejected(List.of(first));rejected(List.of(last));
        rejected(List.of(last,first));rejected(List.of(page("ev_other","topics",0,2,true)));
        rejected(List.of(page(evidence.id(),"summary",0,2,true)));rejected(List.of(page(evidence.id(),"children",0,2,true)));
        accepted(List.of(first,last));accepted(List.of(first,first,last));
    }
    @Test void emptyResultMustStillBeReadFromTheBeginning(){
        accepted(List.of(page(evidence.id(),"topics",0,0,true)));
        rejected(List.of(page(evidence.id(),"topics",100,0,true)));
    }
    @Test void restoredContinuationUsesTheSameReadCoverage(){
        var state=new ExecutionState();var pages=List.of(page(evidence.id(),"topics",0,1,false),page(evidence.id(),"topics",1,1,true));
        state.restore(new PendingOperation.Continuation(new PendingOperation(UUID.randomUUID(),"feedback-analysis",Instant.now().plusSeconds(30)),
                Set.of("audience-report"),pages,MemoryTypes.View.empty(),new PendingOperation.Budget(100,0,0,0,0,false)));
        accepted(state.exchanges());
        state.rollbackOperation(new PendingOperation.Continuation(new PendingOperation(UUID.randomUUID(),"feedback-analysis",Instant.now().plusSeconds(30)),
                Set.of("audience-report"),pages.subList(0,1),MemoryTypes.View.empty(),new PendingOperation.Budget(100,0,0,0,0,false)));
        rejected(state.exchanges());
    }
    @Test void reportRenderingDoesNotRequireMainModelToReadEveryTopic(){
        var report=TaskMeaning.defaults().resolve("视频1001的评论报告，不读取视频内容",LocalDate.now());
        assertTrue(report.analysis().reportRequired());
        assertDoesNotThrow(()->AnalysisExecutionPolicy.requireTopicsRead(report,List.of(evidence),List.of()));
    }
}
