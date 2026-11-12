package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackReportWritingTest {
    private final Selection selection=new Selection(List.of("C:1"),List.of(),"COHERENT",true,"confirmed");
    private final Topic topic=new Topic("confirmed:T1","演唱反馈","观众称赞演唱。","",List.of("C:1","C:2"),2,0,selection);
    private final Input input=new Input(WorkKind.MERGE,List.of(),List.of(topic),null);
    @Test void writingChangesProseAndPreservesEvidenceCountsAndSelection(){
        var result=FeedbackReportWriting.validate("written",input,List.of(new Proposal("T1","忽略模型标题","观众直接称赞演唱，未描述具体技巧。","",List.of(topic.id()))));
        var written=result.topics().get(0);
        assertEquals(topic.title(),written.title());assertEquals(List.of(topic.id()),written.members());
        assertEquals(topic.selection(),written.selection());assertEquals(2,written.messages());
        assertEquals(WorkKind.MERGE,result.kind());
    }
    @Test void missingDuplicatedOrUnknownTopicsAreRejected(){
        assertThrows(AgentException.class,()->FeedbackReportWriting.validate("written",input,List.of()));
        assertThrows(AgentException.class,()->FeedbackReportWriting.validate("written",input,
                List.of(new Proposal("T1","标题","内容","",List.of("unknown")))));
        var proposal=new Proposal("T1","标题","内容","",List.of(topic.id()));
        assertThrows(AgentException.class,()->FeedbackReportWriting.validate("written",input,List.of(proposal,proposal)));
    }
}
