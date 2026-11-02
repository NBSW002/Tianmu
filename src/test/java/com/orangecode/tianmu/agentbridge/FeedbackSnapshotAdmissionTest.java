package com.orangecode.tianmu.agentbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackSnapshotAdmissionTest {
    @Test void invalidRangeAndUnselectedSourcesAreRejectedBeforeDatabaseAccess() {
        var source=mock(DataSource.class);
        var service=new BusinessFeedbackSnapshot(new JdbcTemplate(source),new BridgeProperties("tianmu",Set.of("normal"),"Asia/Shanghai",2),new ObjectMapper());
        var owner=new BusinessAuthentication.Principal("tianmu","881");var start=LocalDateTime.now().minusDays(1).withNano(0);
        assertEquals(400,assertThrows(BridgeException.class,()->service.export(owner,new BusinessFeedbackSnapshot.Request(1001,start,start,Set.of("COMMENT")))).status());
        assertEquals(400,assertThrows(BridgeException.class,()->service.export(owner,new BusinessFeedbackSnapshot.Request(1001,start,start.plusMinutes(1),Set.of("VIDEO")))).status());
        verifyNoInteractions(source);
    }
    @Test void unauthenticatedRequestsCannotReachTheSnapshotService() {
        var auth=mock(BusinessAuthentication.class);var snapshots=mock(BusinessFeedbackSnapshot.class);
        when(auth.authenticate(null)).thenThrow(new BridgeException("UNAUTHORIZED",401));
        assertEquals(401,assertThrows(BridgeException.class,()->new FeedbackSnapshotController(auth,snapshots).export(null,null)).status());
        verifyNoInteractions(snapshots);
    }
    @Test void standaloneBridgeRegistersTheStreamingSnapshotController() {
        var imported=List.of(AgentBridgeApplication.class.getAnnotation(org.springframework.context.annotation.Import.class).value());
        assertTrue(imported.contains(FeedbackSnapshotController.class));
    }
}
