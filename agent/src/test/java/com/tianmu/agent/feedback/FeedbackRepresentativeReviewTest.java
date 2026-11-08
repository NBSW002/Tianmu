package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackRepresentativeReviewTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    final Group group=new Group("B1_COMMENT",Source.COMMENT,"[{\"id\":\"C:1\"},{\"id\":\"C:2\"},{\"id\":\"C:3\"},{\"id\":\"C:4\"}]","",false,null,4,List.of("C:1","C:2","C:3","C:4"),"B1");
    final Topic child=new Topic("work:T1","声音","听不清人声","检查音量",List.of(group.id()),4,0);
    final Input input=new Input(WorkKind.MERGE,List.of(group),List.of(child),null);
    Proposal proposal(Selection selection){return new Proposal("T1","声音","听不清人声","检查音量",List.of(child.id()),selection);}
    Selection selection(List<String> ids,List<Long> frames,String coherence,boolean distinct){return new Selection(ids,frames,coherence,distinct,"所选反馈支撑声音主题");}
    @Test void acceptsNonLeadingRepresentativesAndPersistsTheirOrder()throws Exception{
        var p=proposal(selection(List.of("C:4","C:2","C:3"),List.of(),"COHERENT",true));
        FeedbackRepresentativeReview.validate(input,List.of(p));
        var result=new FeedbackPlanner(new FeedbackObjects(new com.tianmu.agent.adapters.storage.FileObjectStore(directory),new ObjectMapper()),FeedbackPolicy.defaults()).validate("final",input,List.of(p));
        assertEquals(p.selection(),result.topics().get(0).selection());
        var json=new ObjectMapper();assertEquals(result,json.readValue(json.writeValueAsBytes(result),ResultNode.class));
    }
    @Test void rejectsInventedIdsDuplicateIdsUnsupportedFramesAndMissingOrFailedReview(){
        for(var selection:List.of(selection(List.of("C:4","C:2","C:99"),List.of(),"COHERENT",true),selection(List.of("C:4","C:4","C:3"),List.of(),"COHERENT",true),selection(List.of("C:4","C:2","C:3"),List.of(1000L),"COHERENT",true),selection(List.of("C:4","C:2","C:3"),List.of(),"MIXED",true),selection(List.of("C:4","C:2","C:3"),List.of(),"COHERENT",false)))assertThrows(FeedbackCorrectionException.class,()->FeedbackRepresentativeReview.validate(input,List.of(proposal(selection))));
        assertThrows(FeedbackCorrectionException.class,()->FeedbackRepresentativeReview.validate(input,List.of(proposal(null))));
    }
    @Test void unresolvedMixedThemeCanOnlyBeExcludedAfterOneSemanticCorrection(){
        var excluded=proposal(selection(List.of(),List.of(),"EXCLUDED",false));
        assertThrows(FeedbackCorrectionException.class,()->FeedbackRepresentativeReview.validate(input,List.of(excluded)));
        var mixed=proposal(selection(List.of("C:1","C:2","C:3"),List.of(),"MIXED",true));
        var retry=new Input(input.kind(),input.groups(),input.children(),null,new Correction(List.of(mixed),List.of(),List.of(),List.of(),"REPRESENTATIVE_SEMANTIC_REVIEW_FAILED"));
        assertDoesNotThrow(()->FeedbackRepresentativeReview.validate(retry,List.of(excluded)));
        var laterRetry=new Input(input.kind(),input.groups(),input.children(),null,new Correction(List.of(excluded),List.of(),List.of(),List.of(),"REPRESENTATIVE_FRAME_INVALID"));assertDoesNotThrow(()->FeedbackRepresentativeReview.validate(laterRetry,List.of(excluded)));
        var wrongRetry=new Input(input.kind(),input.groups(),input.children(),null,new Correction(List.of(),List.of(),List.of(),List.of(),"MEMBERSHIP_MISMATCH"));
        assertThrows(FeedbackCorrectionException.class,()->FeedbackRepresentativeReview.validate(wrongRetry,List.of(excluded)));
    }
}
