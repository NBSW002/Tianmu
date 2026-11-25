package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.core.FeedbackAnalysis.Topic;
import com.tianmu.agent.feedback.FeedbackModels.Group;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackChatSummaryTest {
    @Test void reviewUsesLocalAudienceEvidenceAndKeepsUntimedAdviceSeparate() {
        var jokes = List.of(bullet("j1","鲸地求生",102_000),bullet("j2","彩鲸6号",102_500));
        var dice = List.of(bullet("d0","博德之门的1就是大失败",38_000),bullet("d1","这个1是什么不重要，这里表达出大概意思就可以",39_590),
                bullet("d2","DND的1就是大失败，20才是大成功",45_160),
                bullet("d3","1大成功是coc吧，dnd1是大失败，一般没有大成功的说法",47_855));
        var comments = List.of(new Group("c1",Source.COMMENT,"太快+1，尤其恐怖游戏那段，还没看清就切走了；慢慢来，本鱼等得起（",
                "",false,null,1,List.of("c1")));
        var notes = List.of(topic("j","游戏名接龙","保留互动。",jokes),
                topic("d","跑团规则中“1”与“20”判定的纠正讨论","若视频涉及跑团判定，可在简介或字幕说明所用规则体系（如DND、COC或博德之门），减少混淆。",dice),
                topic("c","剪辑节奏","延长恐怖游戏镜头。",comments));
        String answer=FeedbackChatSummary.fromNotes(notes,Map.of("j",jokes,"d",dice,"c",comments),7);
        assertTrue(answer.contains("00:38–00:47"),answer);
        assertTrue(answer.contains("「"+dice.get(3).text()+"」"));
        assertTrue(answer.contains("若视频涉及跑团判定，可在简介或字幕说明所用规则体系（如DND、COC或博德之门），减少混淆。"));
        assertTrue(answer.contains("评论（无播放时间）"));
        assertTrue(answer.contains("延长恐怖游戏镜头。"));
        assertFalse(answer.contains("01:42"));
    }

    @Test void dispersedFeedbackDoesNotBecomeAClusterAndAuthorCannotSupplyAudienceEvidence() {
        var groups=List.of(bullet("1","看不清",1_000),bullet("2","还是看不清",80_000),
                new Group("3",Source.DANMAKU,"作者说明","",true,1_500L,1,List.of("3")));
        var note=topic("n","看不清字幕","放大字幕。",groups);
        String answer=FeedbackChatSummary.fromNotes(List.of(note),Map.of("n",groups),3);
        assertTrue(answer.contains("不足以定位"),answer);
        assertFalse(answer.contains("–"));
        assertFalse(answer.contains("作者说明"));
    }

    private static Group bullet(String id,String text,long time) {
        return new Group(id,Source.DANMAKU,text,"",false,time,1,List.of(id));
    }
    private static Topic topic(String id,String title,String advice,List<Group> groups) {
        return new Topic(id,title,title,advice,groups.stream().map(Group::id).toList(),groups.size(),
                groups.stream().filter(Group::author).count());
    }
}
