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
        assertTrue(html.contains("这条视频，观众怎么说"));assertTrue(html.contains("讨论最多的 1 个话题"));assertTrue(html.contains("原话与来源"));assertTrue(html.contains("可以怎么做"));
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
    @Test void mainReportShowsFiveLargestAudienceTopicsWithoutDeletingEvidenceOrInflatingWithAuthors(){
        var roots=new ArrayList<Topic>();var graph=new LinkedHashMap<String,Topic>();var leaves=new LinkedHashMap<String,List<Group>>();
        for(int i=1;i<=7;i++){
            int count=i==7?20:i;boolean isAuthor=i==7;String id="leaf"+i+":T1";
            int ordinal=i;var ids=java.util.stream.IntStream.range(0,count).mapToObj(j->"C:"+(ordinal*100+j)).toList();
            var g=new Group("G"+i,Source.COMMENT,"不同话题原文"+i,"",isAuthor,null,count,ids);
            var t=new Topic(id,"业务话题"+i,"保留该话题观点。","",List.of(g.id()),count,isAuthor?count:0);
            roots.add(t);graph.put(id,t);leaves.put(id,List.of(g));
        }
        var doc=FeedbackReportDocument.build(asset(false,new Counts(41,0,20,0,21)),roots,graph,leaves,Map.of());
        assertEquals(7,doc.cards().size());assertEquals(7,doc.notes().size());assertEquals(5,doc.mainCards().size());
        assertEquals(List.of("业务话题6","业务话题5","业务话题4","业务话题3","业务话题2"),doc.mainCards().stream().map(c->c.topic().title()).toList());
        assertEquals(1,doc.otherAudienceMessages());
        String html=new String(FeedbackReportRenderer.render(doc),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(5,html.split("<article class=\"topic\"",-1).length-1);assertTrue(html.contains("其他话题"));assertTrue(html.contains("业务话题1"));assertTrue(html.contains("业务话题7"));
        assertTrue(html.contains("全部 21 条观众反馈为分母"));assertTrue(html.contains("4.8%"));
    }
    @Test void playbackTrendCountsEveryOriginalAndPreservesZeroBinsInsteadOfPlottingOnlyQuotes(){
        var groups=List.of(new Group("G1",Source.DANMAKU,"重复弹幕","",false,1000L,3,List.of("D:1","D:2","D:3")),
                new Group("G2",Source.DANMAKU,"结尾弹幕","",true,9999L,1,List.of("D:4")),
                new Group("G3",Source.DANMAKU,"位置缺失","",false,null,1,List.of("D:5")),
                new Group("G4",Source.DANMAKU,"上界不含","",false,10000L,1,List.of("D:6")));
        var a=asset(true,new Counts(0,6,0,1,5));var t=FeedbackPlaybackTrend.build(a,groups);
        assertEquals(1000,t.bucketMs());assertEquals(10,t.counts().size());assertEquals(4,t.plotted());assertEquals(2,t.omitted());
        assertEquals(0,t.counts().get(0));assertEquals(3,t.counts().get(1));assertEquals(1,t.counts().get(9));assertTrue(t.knownRange());
        var leaf=new Topic("leaf:T1","弹幕话题","观点","",groups.stream().map(Group::id).toList(),6,1);
        var doc=FeedbackReportDocument.build(a,List.of(leaf),Map.of(leaf.id(),leaf),Map.of(leaf.id(),groups),Map.of("2026-10-20",6L));
        assertEquals(t,doc.playback());String html=new String(FeedbackReportRenderer.render(doc),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("class='playback-chart'"));assertTrue(html.contains("已定位 4 条"));assertTrue(html.contains("2 条位置缺失或超出范围"));
        assertTrue(html.contains("按发送日期查看反馈"));assertFalse(html.contains("<script"));
    }
    @Test void unknownDurationAndUnpositionedFeedbackAreExplicitInsteadOfInventingAVideoRange(){
        var a=asset(false,new Counts(0,3,0,0,3));var unknown=FeedbackPlaybackTrend.build(a,List.of(first,opposite));
        assertFalse(unknown.knownRange());assertEquals(2001,unknown.endMs());assertEquals(3,unknown.plotted());
        var noPositions=FeedbackPlaybackTrend.build(a,List.of(new Group("G1",Source.DANMAKU,"反馈","",false,null,3,List.of("D:1","D:2","D:3"))));
        assertTrue(noPositions.counts().isEmpty());assertEquals(3,noPositions.omitted());
        assertThrows(AgentException.class,()->FeedbackPlaybackTrend.build(a,List.of(first)));
    }
    @Test void compactActionsKeepCompleteConditionsAndFullEvidenceRemainsInsideClosedDetails(){
        String advice="只有在能够确认现场原声收音清晰的前提下，才建议另做一个轻配乐版本；如果无法确认收音条件，不应直接把噪声放大，并且应保留观众提出纯原声版本的相反偏好。";
        var leaf=new Topic("leaf:T1","声音偏好","观众同时提出原声与轻配乐偏好。",advice,List.of("G1","G2","G3"),4,1);
        var doc=FeedbackReportDocument.build(asset(true),List.of(leaf),Map.of(leaf.id(),leaf),Map.of(leaf.id(),List.of(first,opposite,author)),Map.of());
        String html=new String(FeedbackReportRenderer.render(doc),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("只有在能够确认现场原声收音清晰的前提下，才建议另做一个轻配乐版本；"));assertTrue(html.contains(advice));
        assertTrue(html.contains("<details class=\"topic-detail\"><summary>观众原话与完整建议</summary>"));assertFalse(html.contains("<details class=\"topic-detail\" open"));
        assertTrue(html.contains("查看话题占比"));assertTrue(html.contains("这段视频的内容与画面依据"));assertFalse(html.contains("先看结论"));
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
