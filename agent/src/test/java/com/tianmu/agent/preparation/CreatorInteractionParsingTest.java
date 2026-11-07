package com.tianmu.agent.preparation;

import com.tianmu.agent.core.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CreatorInteractionParsingTest {
    final ZonedDateTime now=ZonedDateTime.of(2026,11,2,12,0,0,0,DanmakuTypes.ZONE);
    @Test void sourceOrderDoesNotChangeDraftTargetOrFrozenFeedbackRange(){
        for(String target:List.of("评论","弹幕"))for(String marker:List.of("根据","结合","参考","基于","按照")){
            String before=marker+"视频1001最近7天的评论和弹幕生成"+target+"草稿";
            String after="生成视频1001的"+target+"草稿，"+marker+"最近7天的评论和弹幕";
            var first=CreatorInteractionTask.parse(before,now.toLocalDate(),now);
            var second=CreatorInteractionTask.parse(after,now.toLocalDate(),now);
            assertEquals(first.conditions(),second.conditions(),after);assertEquals(first.analysis(),second.analysis(),after);
            assertEquals(target.equals("评论")?"COMMENT":"DANMAKU",second.conditions().get("kind"));
            assertEquals(Set.of(AnalysisContract.Source.VIDEO,AnalysisContract.Source.COMMENT,AnalysisContract.Source.DANMAKU),second.analysis().sources());
            assertFalse(((TaskConditions.Interaction)second.typed()).feedback().allTime());
        }
    }
    @Test void outputKindDoesNotContaminateTheSelectedFeedbackSource(){
        for(String target:List.of("评论","弹幕")){
            String source=target.equals("评论")?"弹幕":"评论";
            for(String text:List.of("生成视频1001的"+target+"草稿，参考最近7天的"+source,
                    "参考视频1001最近7天的"+source+"生成一条"+target+"草稿")){
                var spec=CreatorInteractionTask.parse(text,now.toLocalDate(),now);
                assertEquals(Set.of(AnalysisContract.Source.VIDEO,source.equals("评论")?AnalysisContract.Source.COMMENT:AnalysisContract.Source.DANMAKU),spec.analysis().sources(),text);
                assertEquals(target.equals("评论")?"COMMENT":"DANMAKU",spec.conditions().get("kind"),text);
            }
        }
    }
    @Test void genuinelyMixedOutputStillRequiresClarification(){
        for(String text:List.of("视频1001生成评论和弹幕草稿","生成视频1001的评论、弹幕草稿，参考最近7天的评论",
                "视频1001的评论和弹幕草稿","参考视频1001的评论生成智能评论和智能弹幕"))
            assertThrows(IllegalArgumentException.class,()->CreatorInteractionTask.parse(text,now.toLocalDate(),now),text);
    }
    @Test void ordinaryDraftDoesNotSelectFeedbackJustBecauseItMentionsItsOutputKind(){
        for(String text:List.of("为视频1001生成智能弹幕草稿","结合视频1001画面写评论","分析视频1001后生成弹幕草稿")){
            var spec=CreatorInteractionTask.parse(text,now.toLocalDate(),now);
            assertNull(((TaskConditions.Interaction)spec.typed()).feedback(),text);
            assertEquals(Set.of(AnalysisContract.Source.VIDEO),spec.reading().sources(),text);
        }
    }
}
