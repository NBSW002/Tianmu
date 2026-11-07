package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import org.springframework.web.bind.annotation.*;

/** Presentation capabilities only; data endpoints still authorize every operation. */
@RestController
@RequestMapping("/api/agent/capabilities")
public final class AssistantCapabilitiesController {
    public record Capabilities(boolean chat, boolean creatorTools, boolean videoContext) {}
    private final IdentityPort identity;
    public AssistantCapabilitiesController(IdentityPort identity) { this.identity = identity; }
    @GetMapping
    public Capabilities get(@RequestHeader(value="Authorization", required=false) String auth,
                            @RequestParam(required=false) Long videoId) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "Login required");
        String credential = auth.substring(7);
        identity.authenticate(credential);
        if (videoId == null) return new Capabilities(true, false, false);
        if (videoId <= 0) throw new AgentException("INVALID_REQUEST", "Invalid video ID");
        try {
            var scope = identity.forCreate(credential, "视频" + videoId, null);
            return new Capabilities(true, scope.videoIds().contains(videoId), scope.readableVideoIds().contains(videoId));
        } catch (AgentException e) {
            if (!"FORBIDDEN".equals(e.code())) throw e;
            return new Capabilities(true, false, false);
        }
    }
}
