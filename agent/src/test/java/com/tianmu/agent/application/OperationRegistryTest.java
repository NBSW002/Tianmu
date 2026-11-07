package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.spi.OperationHandler;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationRegistryTest {
    @Test void newKindHasARealHandlerAndNoKernelSwitch() {
        var calls=new AtomicInteger();var handler=new OperationHandler(){public Set<String> kinds(){return Set.of("example-export");}public void resume(List<RunState> runs){calls.addAndGet(runs.size());}};
        var registry=new OperationRegistry(List.of(handler));var run=mock(RunState.class);var snapshot=mock(AgentTypes.Snapshot.class);
        when(snapshot.operation()).thenReturn(new PendingOperation(UUID.randomUUID(),"example-export",Instant.now().plusSeconds(60)));when(run.snapshot()).thenReturn(snapshot);
        registry.require("example-export");registry.resume(List.of(run));assertEquals(1,calls.get());
        assertThrows(IllegalArgumentException.class,()->new OperationRegistry(List.of(handler,handler)));
        assertEquals("OPERATION_KIND_UNAVAILABLE",assertThrows(AgentException.class,()->registry.require("missing-module")).code());
        var unsupported=mock(RunState.class);var missing=mock(AgentTypes.Snapshot.class);when(unsupported.snapshot()).thenReturn(missing);when(missing.operation()).thenReturn(new PendingOperation(UUID.randomUUID(),"missing-module",Instant.now()));
        registry.resume(List.of(unsupported));verify(unsupported).fail("OPERATION_KIND_UNAVAILABLE");
    }
}
