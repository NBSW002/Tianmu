package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackVectorIndex.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackVectorIndexTest {
    Unit comment(String text,String parent,boolean author,String user,Instant at){
        return new Unit("C:1",Source.COMMENT,"1",text,parent,author,null,user,at);
    }
    @Test void editingTextReplyContextOrAttributionInvalidatesEvidenceVersion(){
        var at=Instant.parse("2026-11-05T00:00:00Z");
        var original=Reference.from(comment("同意","父评论",false,"u1",at));
        assertEquals(original,Reference.from(comment("同意","父评论",false,"u1",at)));
        assertNotEquals(original,Reference.from(comment("不同意","父评论",false,"u1",at)));
        assertNotEquals(original,Reference.from(comment("同意","修改后的父评论",false,"u1",at)));
        assertNotEquals(original,Reference.from(comment("同意","父评论",true,"u1",at)));
        assertNotEquals(original,Reference.from(comment("同意","父评论",false,"u2",at)));
        assertNotEquals(original,Reference.from(comment("同意","父评论",false,"u1",at.plusSeconds(1))));
    }
    @Test void identicalDanmakuInDifferentPlaybackContextsCannotShareEvidenceIdentity(){
        var a=new Unit("D:1",Source.DANMAKU,"1","哈哈","",false,1000L,"u1");
        var b=new Unit("D:1",Source.DANMAKU,"1","哈哈","",false,5000L,"u1");
        assertNotEquals(Reference.from(a),Reference.from(b));
    }
    @Test void fieldBoundariesAndNullsCannotCollide(){
        assertNotEquals(Reference.from(comment("a|b","c",false,null,null)),
                Reference.from(comment("a","b|c",false,null,null)));
        assertNotEquals(Reference.from(comment("a","",false,null,null)),
                Reference.from(comment("a","",false,"",null)));
    }
}
