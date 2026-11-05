package com.tianmu.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import com.tianmu.agent.access.AssistantToolAccess;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantScopeTest {
    @Test void publicReadDoesNotGrantCreatorToolsOrHistoricalCreatorResults() {
        var viewer=new AccessScope("site","1",Set.of(),"ref",Set.of(10L));
        var creator=new AccessScope("site","1",Set.of(10L));
        assertFalse(viewer.covers(creator)); assertTrue(creator.covers(viewer));
        assertTrue(AssistantToolAccess.permits("analyze_video_content",viewer));
        assertFalse(AssistantToolAccess.permits("prepare_creator_interactions",viewer));
        assertFalse(AssistantToolAccess.permits("analyze_feedback",viewer));
        var repository=new MemoryRunRepository();
        var run=repository.create("private analysis",creator,null,null,null).run();
        assertThrows(AgentException.class,()->repository.load(run.id,viewer));
        assertThrows(AgentException.class,()->repository.resourcesForSession(run.sessionId,new AccessScope("site","2",Set.of(10L)),Long.MAX_VALUE));
    }
    @Test void legacyScopesRemainCreatorOnlyAndNewPublicGrantsSurviveSerialization() throws Exception {
        var json=new ObjectMapper();
        var old=json.readValue("{\"tenantId\":\"site\",\"userId\":\"1\",\"videoIds\":[10]}",AccessScope.class);
        assertEquals(Set.of(10L),old.videoIds());assertEquals(Set.of(10L),old.readableVideoIds());
        var scope=new AccessScope("site","1",Set.of(),"ref",Set.of(20L));
        assertEquals(scope,json.readValue(json.writeValueAsBytes(scope),AccessScope.class));
        assertThrows(IllegalArgumentException.class,()->new AccessScope("site","1",Set.of(10L),"",Set.of(20L)));
    }
}
