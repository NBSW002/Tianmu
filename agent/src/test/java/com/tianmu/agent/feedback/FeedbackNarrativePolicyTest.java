package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackNarrativePolicyTest {
    Input source(String text){return new Input(WorkKind.CLASSIFY,List.of(new Group("G1",Source.COMMENT,text,"",true,null,3,List.of("C:1","C:2","C:3"))),List.of(),null);}
    List<Proposal> prose(String observation){return List.of(new Proposal("T1","作者说明与观众意见",observation,"可对比两种剪辑方案。",List.of("G1")));}
    @Test void observedContradictionAndEvenCorrectDatasetCountsRequireHostRendering(){
        for(String text:List.of("作者两条发言承担不同功能，三条均为作者自述。","作者共有3条发言。","作者发言共三条。","三条均为作者自述。","80%的观众希望保留原声。")){
            var failure=assertThrows(FeedbackCorrectionException.class,()->FeedbackNarrativePolicy.validate(source("说明背景"),prose(text)));
            assertEquals("NARRATIVE_DATASET_COUNTS",failure.correction().issue());
        }
    }
    @Test void internalReferencesAreCorrectedInsteadOfStrippedOrAccepted(){
        for(String text:List.of("G1补充背景。","来自C1和C2的反馈有分歧。","K2聚类包含反对意见。","引用5372178a-45e0-4f9b-95cf-bb8e2dfd3488:T1。"))
            assertEquals("NARRATIVE_INTERNAL_REFERENCE",assertThrows(FeedbackCorrectionException.class,()->FeedbackNarrativePolicy.validate(source("不加配乐更自然"),prose(text))).correction().issue());
    }
    @Test void sourceNumbersModelsAndConditionalRecommendationsRemainIntact(){
        var input=source("G1相机价格1200元，视频30秒，音乐音量50%。我发了两条评论。");
        assertDoesNotThrow(()->FeedbackNarrativePolicy.validate(input,prose("使用G1相机，价格1200元，片段30秒。观众说“我发了两条评论”，这是其自述。可以把音量调至50%。")));
        assertThrows(FeedbackCorrectionException.class,()->FeedbackNarrativePolicy.validate(input,prose("观众说“作者共有999条发言”。")),"Invented quotation is not evidence");
    }
    @Test void realModelIdentifiersDoNotExemptPrefixCollisions(){
        assertThrows(FeedbackCorrectionException.class,()->FeedbackNarrativePolicy.validate(source("G10相机"),prose("G1补充背景")));
    }
    @Test void acceptedQuotesSurviveHierarchicalSynthesis(){
        var child=new Topic("work:T1","原声分歧","观众说“我发了两条评论”，另一种观点希望增加轻配乐。","可保留原声版与配乐版。",List.of("G1"),3,1);
        var input=new Input(WorkKind.MERGE,List.of(),List.of(child),null);
        assertDoesNotThrow(()->FeedbackNarrativePolicy.validate(input,prose("观众自述“我发了两条评论”，与另一种轻配乐偏好并存。")));
        assertThrows(FeedbackCorrectionException.class,()->FeedbackNarrativePolicy.validate(input,prose("C1包含原声分歧。")));
    }
}
