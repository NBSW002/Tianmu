package com.tianmu.agent.application;

import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalReportReaderTest {
    @Test void readsTheExactSavedSourceAndPreservesHistoricalTimeWithoutModels(){
        var repo=new MemoryRunRepository();var owner=new AccessScope("demo","alice",Set.of(1001L,1002L));
        var run=repo.create("视频报告",owner,null,null,null).run();run.start();
        var old=Instant.parse("2026-10-01T00:00:00Z");
        var video=new VideoContent.Facts("video-content-v1",1001,"version-a",0,10000,"早期视频画面",List.of(new VideoContent.Moment(1000,"早期画面观察")),"","SAMPLED_FRAMES",List.of(1000L),"fixture",old);
        String id="ev_"+UUID.randomUUID();run.execution().evidence().put(new Evidence(id,run.id,owner,null,null,video));
        var report=repo.saveReport(run.id,owner,id,"<html>saved</html>".getBytes());run.succeed(new Answer("saved",List.of(id)));
        var reader=new HistoricalReportReader(repo,repo,null);var source=reader.source(run.id,report.artifactId(),owner,new TaskTypes.Specification("resource.lookup",Map.of(),Map.of()));
        var result=LookupFacts.normalize(reader.read(source,report.artifactId(),"summary",0,10));
        assertEquals(true,result.get("historical"));assertEquals("SOURCE_SNAPSHOT_ONLY",result.get("freshness"));
        assertEquals(old.toString(),result.get("data_as_of"));assertNotEquals(result.get("data_as_of"),result.get("generated_at"));
        assertEquals(id,source.report().evidenceId());
        assertEquals("TASK_CONSTRAINT_VIOLATION",assertThrows(AgentException.class,()->reader.source(run.id,report.artifactId(),owner,
                new TaskTypes.Specification("video.content",Map.of("video_id",1002),Map.of()))).code());
        assertThrows(AgentException.class,()->reader.source(run.id,report.artifactId(),new AccessScope("demo","bob",Set.of(1001L)),null));
        assertThrows(AgentException.class,()->reader.source(run.id,report.artifactId(),new AccessScope("demo","alice",Set.of(1002L)),null));
        assertThrows(AgentException.class,()->reader.source(run.id,"rp_"+UUID.randomUUID(),owner,null));
    }
}
