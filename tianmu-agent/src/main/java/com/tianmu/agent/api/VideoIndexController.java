package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.video.index.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/agent/videos/{videoId}/index")
public final class VideoIndexController {
    private final IdentityPort identity;private final VideoIndexService indexes;
    public VideoIndexController(IdentityPort identity,VideoIndexService indexes){this.identity=identity;this.indexes=indexes;}
    private AccessScope scope(String auth,long video){
        if(auth==null || !auth.startsWith("Bearer "))throw new AgentException("UNAUTHORIZED","Login required");
        if(video<=0)throw new AgentException("INVALID_REQUEST","Invalid video ID");
        return identity.forCreate(auth.substring(7),"",null,new RequestContext(Long.toString(video)),null);
    }
    @GetMapping public Object get(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable long videoId){var value=indexes.get(scope(auth,videoId),videoId);return value==null?java.util.Map.of("status","NOT_INDEXED","chunks",java.util.List.of()):value;}
    @PostMapping public VideoIndex start(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable long videoId){return indexes.start(scope(auth,videoId),videoId);}
    @DeleteMapping public Object cancel(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable long videoId){var value=indexes.cancel(scope(auth,videoId),videoId);return value==null?java.util.Map.of("status","NOT_INDEXED"):value;}
}
