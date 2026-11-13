package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.FeedbackAnalysis.Source;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackModelEvidenceTest {
    @Test void evidencePreservesPlaybackDuplicateCountAndRealZeroLikes() {
        var group=new FeedbackModels.Group("D:1",Source.DANMAKU,"这个镜头很好","",false,1234L,7,List.of("D:1"),"B1",0L);
        var row=FeedbackRerankConfirmation.modelEvidence(group);
        assertEquals(1234L,row.get("playbackMs"));assertEquals(7L,row.get("exactMessages"));
        assertEquals(0L,row.get("likes"));assertEquals(true,row.get("likesAvailable"));
    }

    @Test void oldStoredEvidenceWithMissingLikesRemainsMissing() throws Exception {
        var json=new ObjectMapper();
        var old=json.writeValueAsString(new FeedbackModels.Group("C:1",Source.COMMENT,"字幕太小","",false,null,1,List.of("C:1"),"B1"));
        var tree=json.readTree(old);((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("likes");
        var restored=json.treeToValue(tree,FeedbackModels.Group.class);
        var row=FeedbackRerankConfirmation.modelEvidence(restored);
        assertNull(row.get("playbackMs"));assertNull(row.get("likes"));assertEquals(false,row.get("likesAvailable"));
    }

    @Test void selectionCapsBothRankedAndFallbackMaterialsAtFive() {
        assertEquals(List.of(6,5,4,3,2),FeedbackReranker.topEvidence(7,
                new FeedbackReranker.Result(List.of(0d,1d,2d,3d,4d,5d,6d),null)));
        assertEquals(List.of(0,1,2,3,4),FeedbackReranker.topEvidence(7,null));
        assertEquals(List.of(0,1),FeedbackReranker.topEvidence(2,null));
    }
}
