package com.tianmu.agent;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.api.AssistantCapabilitiesController;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantCapabilitiesTest {
    private final IdentityPort identity = mock(IdentityPort.class);
    private final AssistantCapabilitiesController controller = new AssistantCapabilitiesController(identity);
    @Test void anyAuthenticatedUserCanChatWithoutOwningVideos() {
        when(identity.authenticate("viewer")).thenReturn(new AccessScope("site", "2", Set.of()));
        assertEquals(new AssistantCapabilitiesController.Capabilities(true, false, false), controller.get("Bearer viewer", null));
        verify(identity, never()).forCreate(anyString(), anyString(), any());
    }
    @Test void anotherUsersVideoNeverEnablesCreatorTools() {
        when(identity.forCreate("viewer", "视频1001", null)).thenThrow(new AgentException("FORBIDDEN", "not owner"));
        assertEquals(new AssistantCapabilitiesController.Capabilities(true, false, false), controller.get("Bearer viewer", 1001L));
    }
    @Test void publishedVideoEnablesContentWithoutCreatorTools() {
        when(identity.forCreate("viewer", "视频1001", null)).thenReturn(new AccessScope("site", "2", Set.of(), "", Set.of(1001L)));
        assertEquals(new AssistantCapabilitiesController.Capabilities(true, false, true), controller.get("Bearer viewer",1001L));
    }
    @Test void creatorCapabilitiesRequireServerGrant() {
        when(identity.forCreate("author", "视频1001", null)).thenReturn(new AccessScope("site", "1", Set.of(1001L)));
        assertTrue(controller.get("Bearer author", 1001L).creatorTools());
    }
    @Test void authenticationAndInfrastructureErrorsAreNotDowngraded() {
        assertEquals("UNAUTHORIZED", assertThrows(AgentException.class, () -> controller.get(null, null)).code());
        when(identity.authenticate("expired")).thenThrow(new AgentException("UNAUTHORIZED", "expired"));
        assertThrows(AgentException.class, () -> controller.get("Bearer expired", null));
        when(identity.forCreate("user", "视频1001", null)).thenThrow(new AgentException("BUSINESS_UNAVAILABLE", "offline"));
        assertEquals("BUSINESS_UNAVAILABLE", assertThrows(AgentException.class, () -> controller.get("Bearer user", 1001L)).code());
        assertEquals("INVALID_REQUEST", assertThrows(AgentException.class, () -> controller.get("Bearer user", -1L)).code());
    }
}
