package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.spi.OperationHandler;
import java.util.*;
import org.springframework.stereotype.Component;

/** A kind is available only when exactly one real resumption handler is registered. */
@Component
public final class OperationRegistry {
    private final Map<String,OperationHandler> handlers;
    public OperationRegistry(List<OperationHandler> registered) {
        var result=new HashMap<String,OperationHandler>();
        for(var handler:registered) {
            if(handler.kinds()==null || handler.kinds().isEmpty())throw new IllegalArgumentException("Missing operation kinds");
            for(String kind:handler.kinds())if(kind==null || !kind.matches("[a-z][a-z0-9.-]{0,63}") || result.putIfAbsent(kind,handler)!=null)throw new IllegalArgumentException("Invalid or duplicate operation kind");
        }
        handlers=Map.copyOf(result);
    }
    public void require(String kind){if(!handlers.containsKey(kind))throw new AgentException("OPERATION_KIND_UNAVAILABLE","The operation handler is unavailable");}
    public void resume(List<RunState> waiting) {
        var groups=new LinkedHashMap<OperationHandler,List<RunState>>();
        for(var run:waiting){var op=run.snapshot().operation();if(op==null)continue;var handler=handlers.get(op.kind());
            if(handler==null){run.fail("OPERATION_KIND_UNAVAILABLE");continue;}
            groups.computeIfAbsent(handler,key->new ArrayList<>()).add(run);
        }
        groups.forEach((handler,runs)->{try{handler.resume(List.copyOf(runs));}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("operation_dispatch_failed type={}",failure.getClass().getSimpleName());}});
    }
}
