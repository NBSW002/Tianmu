package com.tianmu.agent.video.index;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AgentException;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

@Component @ConditionalOnProperty(name="agent.storage.mode",havingValue="memory",matchIfMissing=true)
public final class MemoryVideoIndexStore implements VideoIndexStore {
    private record Key(String tenant,String user,long video){}
    private final Map<Key,VideoIndex> entries=new HashMap<>();
    private Key key(AccessScope scope,long video){return new Key(scope.tenantId(),scope.userId(),video);}
    public synchronized VideoIndex get(AccessScope scope,long video){return entries.get(key(scope,video));}
    public synchronized VideoIndex start(AccessScope scope,VideoIndex proposed){
        var key=key(scope,proposed.videoId());var existing=entries.get(key);
        if(existing!=null && existing.active())return existing;
        if(existing==null && entries.size()>=200)throw new AgentException("CAPACITY_EXCEEDED","Index capacity reached");
        entries.put(key,proposed);return proposed;
    }
    public synchronized boolean replace(AccessScope scope,VideoIndex expected,VideoIndex updated){return entries.replace(key(scope,expected.videoId()),expected,updated);}
}
