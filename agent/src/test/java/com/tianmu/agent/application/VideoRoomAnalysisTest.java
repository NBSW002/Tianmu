package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.config.ExecutionConfiguration;
import com.tianmu.agent.model.AudienceModelPort;
import com.tianmu.agent.video.VideoAnalysisService;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoRoomAnalysisTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AccessScope owner=new AccessScope("t","alice",Set.of(1001L));
    Overview audience(){return new Overview(List.of(new Interaction("9",3,2,2,Instant.now())),List.of(new Position(0,30000,3)),1,
            List.of(new TextGroup("1","尺寸？",3,2500L,false),new TextGroup("2","颜色？",1,null,false)),4,4);}
    Dataset data(Query q){return new Dataset(q,4,2,java.util.stream.IntStream.range(0,12).mapToObj(i->new Bucket(i,i==0?4:0)).toList(),List.of(),Instant.now(),"fixture",false,audience());}
    @Test void defaultsUseFrozenSubmissionInstantAndExplicitRangesRemainUnchanged(){
        Instant at=Instant.parse("2026-10-26T13:20:33.999Z");LocalDate date=LocalDate.of(2026,10,26);
        var spec=TaskMeaning.defaults().resolve("分析视频1001弹幕并生成报告",date,at);
        assertEquals("1970-01-01T00:00",spec.conditions().get("start"));
        assertEquals("2026-10-26T21:20:33",spec.conditions().get("end"));
        assertEquals("danmaku.overview",TaskMeaning.defaults().resolve("视频1001有多少弹幕",date,at).type());
        var explicit=DanmakuReportTask.parse("分析视频1001从2026-10-20 10:00到2026-10-26 10:00弹幕报告",date);
        assertEquals(LocalDateTime.of(2026,10,20,10,0),explicit.start());
        assertThrows(IllegalArgumentException.class,()->DanmakuReportTask.parse("分析视频1001从2026-10-20 10:00弹幕报告",date));
    }

    @Test void videoContextRequiresActualSampledTimestamp(){
        var video=new VideoContent.Facts("video-content-v1",1001,"v",2000,6000,"静态山水",List.of(new VideoContent.Moment(2500,"山水")),"","SAMPLED_FRAMES",List.of(2500L),"vision",Instant.now());
        String source="[{\"title\":\"尺寸\",\"observation\":\"提问\",\"recommendation\":\"\",\"sample_ids\":[\"1\"],\"video_context\":\"采样画面有山水\",\"video_at_ms\":[2500]}]";
        assertEquals(1,AudienceTopics.parse(json,source,audience(),video).size());
        assertThrows(AgentException.class,()->AudienceTopics.parse(json,source,audience(),null));
        assertThrows(AgentException.class,()->AudienceTopics.parse(json,source.replace("2500","2501"),audience(),video));
    }

}
