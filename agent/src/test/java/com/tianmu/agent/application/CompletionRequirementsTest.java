package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.TaskTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.spi.CompletionEvaluator;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletionRequirementsTest {
    @Test void unconfirmedMemoryClaimsDoNotFailOrdinaryAnswersOrRemoveTheirContent() {
        assertEquals("好的，你喜欢通过例子学习。", MemoryReceiptRequirement.withoutUnconfirmedClaims("记住了，你喜欢通过例子学习。"));
        assertEquals("你好。好的，你喜欢简洁的回答。", MemoryReceiptRequirement.withoutUnconfirmedClaims("你好。已经记下了，你喜欢简洁的回答。"));
        assertEquals("这是一个例子。", MemoryReceiptRequirement.withoutUnconfirmedClaims("这是一个例子。"));
        assertFalse(MemoryReceiptRequirement.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
                "The pre-delivery evaluator must not reject a response before it can be corrected");
    }
    final UUID id=UUID.randomUUID();final AccessScope owner=new AccessScope("test","alice",Set.of(1001L));
    final BusinessCompletionEvaluator evaluator=new BusinessCompletionEvaluator();
    PreparedTask task(String query) {
        var spec=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve(query,LocalDate.now(),Instant.now());
        return new PreparedTask(id,query,query,LocalDate.now(),"Asia/Shanghai",owner,new PreparedRequest(query,query,Emotion.UNKNOWN,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec),spec,Map.of(),Set.of(),Instant.now().plusSeconds(3600),16);
    }
    Evidence evidence(PreparedTask task,Set<FeedbackAnalysis.Source> sources) {
        var expected=FeedbackTaskPolicy.request(task.specification());
        var request=new FeedbackAnalysis.Request(expected.query(),sources,expected.grounding(),expected.videoStartMs(),expected.videoEndMs(),expected.rulesVersion());
        return new Evidence("ev_fixture",id,owner,null,null,null,null,null,new FeedbackAnalysis.Binding(UUID.randomUUID(),request,new FeedbackAnalysis.Counts(1,1,0,0,2),Instant.now(),"hash","fixture","fixture"));
    }
    @Test void registeredReportReceiptCannotCompleteAnUncoveredSource() {
        var task=task("视频1001的评论和弹幕报告，不读取视频内容");
        var result=new ToolResult("create_feedback_report",Map.of("status","READY","url","/private/report","artifact_id","rp_fixture"),"ev_fixture");
        var exchanges=List.of(new ToolExchange(new ToolCall("create_feedback_report",Map.of()),result));
        var partial=evidence(task,Set.of(FeedbackAnalysis.Source.COMMENT));
        assertInstanceOf(CompletionEvaluator.Continue.class,evaluator.evaluate(new CompletionEvaluator.Input(task,exchanges,Set.of(partial.id()),null,null,StopReason.ANSWER,Map.of(partial.id(),partial))));
        var complete=evidence(task,Set.of(FeedbackAnalysis.Source.COMMENT,FeedbackAnalysis.Source.DANMAKU));
        assertInstanceOf(CompletionEvaluator.Deliver.class,evaluator.evaluate(new CompletionEvaluator.Input(task,exchanges,Set.of(complete.id()),null,null,StopReason.ANSWER,Map.of(complete.id(),complete))));
    }
    @Test void analysisProposalRequiresContiguousRecordedTopicReading() {
        var task=task("分析视频1001的评论");var item=evidence(task,Set.of(FeedbackAnalysis.Source.COMMENT));var answer=new Answer("已分析",List.of(item.id()));
        var missing=evaluator.evaluate(new CompletionEvaluator.Input(task,List.of(),Set.of(),answer,null,StopReason.ANSWER,Map.of(item.id(),item)));
        assertEquals("INVALID_EVIDENCE",assertInstanceOf(CompletionEvaluator.Reject.class,missing).code());
        var read=new ToolExchange(new ToolCall("read_result",Map.of("result_id",item.id(),"section","topics","offset",0)),new ToolResult("read_result",Map.of("topics",List.of("主题"),"next_offset",1,"complete",true),item.id()));
        assertInstanceOf(CompletionEvaluator.Deliver.class,evaluator.evaluate(new CompletionEvaluator.Input(task,List.of(read),Set.of(),answer,null,StopReason.ANSWER,Map.of(item.id(),item))));
    }
}
