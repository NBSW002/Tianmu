package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FiveBucketPlannerTest {
    @Test void separateBucketsCannotBeMergedAndTwoSourcesInOneBucketMustStayTogether(){
        var planner=new FeedbackPlanner(null,FeedbackPolicy.defaults());
        var groups=List.of(new FeedbackModels.Group("B1_COMMENT",Source.COMMENT,"[]","",false,null,1,List.of("C:1"),"B1"),
                new FeedbackModels.Group("B1_DANMAKU",Source.DANMAKU,"[]","",false,null,1,List.of("D:1"),"B1"),
                new FeedbackModels.Group("B2_COMMENT",Source.COMMENT,"[]","",false,null,1,List.of("C:2"),"B2"));
        var input=new FeedbackModels.Input(WorkKind.CLASSIFY,groups,List.of(),null);
        var merged=List.of(new FeedbackModels.Proposal("T1","标题","观察","建议",List.of("B1_COMMENT","B1_DANMAKU","B2_COMMENT")));
        assertEquals("REPRESENTATIVE_BUCKET_MERGED",assertThrows(FeedbackCorrectionException.class,()->planner.validate(UUID.randomUUID().toString(),input,merged)).correction().issue());
        var separate=List.of(new FeedbackModels.Proposal("T1","标题一","观察","建议",List.of("B1_COMMENT","B1_DANMAKU")),new FeedbackModels.Proposal("T2","标题二","观察","建议",List.of("B2_COMMENT")));
        var result=planner.validate(UUID.randomUUID().toString(),input,separate);assertEquals(2,result.topics().size());assertEquals(3,result.covered());
    }
}
