package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.Group;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackReportDocumentTest {
    final Group first=new Group("G1",Source.DANMAKU,"希望保留原声","",false,1000L,2,List.of("D:1","D:2"));
    final Group opposite=new Group("G2",Source.DANMAKU,"想加一点轻配乐","",false,2000L,1,List.of("D:3"));
    final Group author=new Group("G3",Source.COMMENT,"我是作者，现场有鸭叫","上文<script>unsafe</script>",true,null,1,List.of("C:4"));
    Topic topic(String id,List<String> members,long count,long authors){return new Topic(id,"原声与配乐偏好","希望保留原声，也有人想加一点轻配乐。","先核对音轨，再决定是否提供对比版。",members,count,authors);}
    Asset asset(boolean grounded){return asset(grounded,new Counts(1,3,1,0,3));}
    Asset asset(boolean grounded,Counts counts){
        var request=new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,10,20,0,0),LocalDateTime.of(2026,10,21,0,0)),Set.of(Source.COMMENT,Source.DANMAKU),grounded?Grounding.VIDEO_GROUNDED:Grounding.FEEDBACK_ONLY,null,null,"feedback-pipeline-v2");
        var video=grounded?new VideoContent.Facts("video-content-v1",1001,"source",0,10000,"草地上的小鸭",List.of(new VideoContent.Moment(1500,"小鸭在草地上")),"","SAMPLED_FRAMES",List.of(1500L),"fixture",Instant.now()):null;
        return new Asset(UUID.randomUUID(),UUID.randomUUID(),"t","u",request,new Snapshot("feedback-snapshot-v1",Instant.now(),counts,List.of(),"a".repeat(64)),List.of(),"fixture","policy",video);
    }
    @Test void nestedTopicsRetainExactSourceCountsOpposingQuotesAndNearbyFrameWithoutAuthorInflation(){
        var leaf=topic("leaf:T1",List.of("G1","G2","G3"),4,1);var middle=topic("middle:T1",List.of(leaf.id()),4,1);var root=topic("root:T1",List.of(middle.id()),4,1);
        var doc=FeedbackReportDocument.build(asset(true),List.of(root),Map.of(leaf.id(),leaf,middle.id(),middle),Map.of(leaf.id(),List.of(first,opposite,author)),Map.of("2026-10-20",4L));
        var card=doc.cards().get(0);assertEquals(3,card.danmaku());assertEquals(1,card.comments());assertEquals(2,card.quotes().size());
        assertTrue(card.quotes().stream().noneMatch(FeedbackReportDocument.Quote::author));assertEquals(Set.of(first.text(),opposite.text()),new HashSet<>(card.quotes().stream().map(FeedbackReportDocument.Quote::text).toList()));
        assertEquals(1500,card.moments().get(0).atMs());
        String html=new String(FeedbackReportRenderer.render(doc),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("先看结论"));assertTrue(html.contains("观众在讨论什么"));assertTrue(html.contains("原话与来源"));assertTrue(html.contains("可以怎么做"));
        assertTrue(html.contains("/video/1001?t=1.0"));assertTrue(html.contains("target=\"_blank\" rel=\"noopener noreferrer\""));assertTrue(html.contains("不证明反馈中的声音"));assertFalse(html.contains("root:T1"));assertFalse(html.contains("层级"));
    }
    @Test void commentOnlyEvidenceNeverManufacturesPlaybackOrTopicFrameAssociation(){
        var leaf=topic("leaf:T1",List.of("G3"),1,1);
        var doc=FeedbackReportDocument.build(asset(true,new Counts(1,0,1,0,0)),List.of(leaf),Map.of(leaf.id(),leaf),Map.of(leaf.id(),List.of(author)),Map.of());
        assertTrue(doc.cards().get(0).moments().isEmpty());assertNull(doc.cards().get(0).quotes().get(0).playbackMs());
        String html=new String(FeedbackReportRenderer.render(doc),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("&lt;script&gt;unsafe&lt;/script&gt;"));assertFalse(html.contains("<script>"));assertTrue(html.contains("作者 · 评论"));
    }
    @Test void duplicateDisplayQuotesAreHiddenWithoutChangingMembershipCounts(){
        var a=topic("a:T1",List.of("G1"),2,0);var b=topic("b:T1",List.of("G4"),1,0);
        var duplicate=new Group("G4",Source.COMMENT,first.text(),"",false,null,1,List.of("C:5"));
        var doc=FeedbackReportDocument.build(asset(false,new Counts(1,2,0,0,3)),List.of(a,b),Map.of(a.id(),a,b.id(),b),Map.of(a.id(),List.of(first),b.id(),List.of(duplicate)),Map.of());
        assertEquals(2,doc.cards().get(0).topic().messages());assertEquals(1,doc.cards().get(1).topic().messages());assertEquals(1,doc.cards().stream().mapToInt(c->c.quotes().size()).sum());
    }
    @Test void structuredReplyChainsShowSourceTextWithoutTransportIdsOrJson(){
        String raw="[{\"id\":\"7520745728063299600\",\"author\":false,\"text\":\"绒毛很可爱，但不要随便摸\"},{\"id\":\"7520745728063299601\",\"author\":true,\"text\":\"这里是作者说明\"}]";
        String shown=FeedbackReportDocument.replyContext(raw);assertEquals("上文：绒毛很可爱，但不要随便摸\n作者：这里是作者说明",shown);assertFalse(shown.contains("752074"));
        assertEquals("",FeedbackReportDocument.replyContext(""));assertEquals("上文 · 观众：保留原话",FeedbackReportDocument.replyContext("评论 123 · 观众：保留原话"));
        assertFalse(FeedbackReportDocument.replyContext("[{broken-json]").contains("broken-json"));
    }
    @Test void missingOrRepeatedDescendantsCannotProduceACompleteLookingReport(){
        var root=topic("root:T1",List.of("missing:T1"),1,0);
        assertEquals("STORAGE_CORRUPT",assertThrows(AgentException.class,()->FeedbackReportDocument.build(asset(false),List.of(root),Map.of(),Map.of(),Map.of())).code());
        var leaf=topic("leaf:T1",List.of("G1"),2,0);var duplicate=topic("middle:T1",List.of(leaf.id()),2,0);var bad=topic("root:T1",List.of(leaf.id(),duplicate.id()),4,0);
        assertThrows(AgentException.class,()->FeedbackReportDocument.build(asset(false),List.of(bad),Map.of(leaf.id(),leaf,duplicate.id(),duplicate),Map.of(leaf.id(),List.of(first)),Map.of()));
    }
}
