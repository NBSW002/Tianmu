package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.Source;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackEditorialLayoutTest {
    @Test void rendersNavigableReportWithoutRequiringAdviceForEveryTopic() throws Exception {
        var quote=new FeedbackCompression.Representative("C:1",Source.COMMENT,"人声很清晰，副歌这一段尤其好听。","",null,12L,1,false,null,null);
        var topic=new FeedbackReportJson.TopicView("T1","演唱获得认可","观众具体提到了副歌和人声的清晰度。","下一条作品可延续目前的人声与伴奏比例。",1,0,List.of(quote));
        var reaction=new FeedbackCompression.Representative("C:2",Source.COMMENT,"一开口就惊到了，反差太大了。","",null,null,1,false,null,null);
        var observation=new FeedbackReportJson.TopicView("T2","声音与形象形成反差","部分观众表达了听到歌声之后的惊讶。","",1,0,List.of(reaction));
        var report=new FeedbackReportJson("feedback-report-json-v1","观众反馈报告","主要反馈涉及演唱表现与声音反差。",List.of(topic,observation),List.of(),
                Map.of("raw",6179L,"eligible",3419L,"topics",2L),"一段在麦克风前的现场演唱。",Map.of("video_id","1001","start","1970-01-01T00:00","end","2026-11-11T22:25"),Instant.parse("2026-11-11T14:25:00Z"),null,List.of(),Map.of(),null,null)
                .withVideoTitle("一身肌肉之下，藏着碇真嗣 · One Last Kiss");
        String cover="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jC1sAAAAASUVORK5CYII=";
        String picture=System.getProperty("report.preview.cover");
        if(picture!=null){
            var source=javax.imageio.ImageIO.read(Path.of(picture).toFile());
            var resized=new java.awt.image.BufferedImage(640,360,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=resized.createGraphics();g.drawImage(source,0,0,640,360,null);g.dispose();
            var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(resized,"jpg",bytes);
            cover="data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
        var html=new String(report.withVideoMetadata("一身肌肉之下，藏着碇真嗣 · One Last Kiss",cover).html(),StandardCharsets.UTF_8);
        assertFalse(html.contains("class='coverage'"));assertFalse(html.contains("筛选后材料"));
        assertTrue(html.contains("共采集 6,179 条评论与弹幕"));
        assertTrue(html.contains("<figure class='video-cover'><img"));
        assertTrue(html.indexOf("</h1>")<html.indexOf("<figure class='video-cover'"));
        assertTrue(html.indexOf("<figure class='video-cover'")<html.indexOf("<div class='report-meta'"));assertTrue(html.contains(cover));
        assertFalse(new String(report.withVideoMetadata("标题","javascript:alert(1)").html(),StandardCharsets.UTF_8).contains("<figure"));
        assertTrue(html.contains("data-report-layout='editorial-v2'"));
        assertFalse(html.contains("<nav"));
        assertFalse(html.contains("本期内容"));assertTrue(html.contains("<article id='topic-2'"));
        assertTrue(html.contains("<section id='creation'>"));assertFalse(html.contains("<div class='action'>"));
        assertEquals(1,html.split(topic.recommendation(),-1).length-1);
        assertFalse(html.contains("更多原话与片段依据"));
        assertTrue(html.contains("不代表逐条阅读全部反馈"));assertTrue(html.contains("不代表观众留存率"));
        assertTrue(html.contains("一身肌肉之下，藏着碇真嗣"));
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/report-editorial-quality-preview.html"),html,StandardCharsets.UTF_8);
    }
}
