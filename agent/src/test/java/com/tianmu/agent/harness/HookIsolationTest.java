package com.tianmu.agent.harness;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.hook.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HookIsolationTest {
    HookEvent event(){return new HookEvent("1",HookPoint.PRE_MODEL,UUID.randomUUID(),"","",0,Instant.now(),Map.of());}
    @Test void uncooperativeGateKeepsItsWorkerAndSaturationFailsClosed() throws Exception {
        var release=new CountDownLatch(1);var entered=new AtomicInteger();
        var hook=HarnessLifecycleTest.hook("blocked",0,AgentHook.Mode.GATE,Set.of(HookPoint.PRE_MODEL),e->{entered.incrementAndGet();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}});
        var callers=Executors.newFixedThreadPool(6);
        try(var dispatcher=new HookDispatcher(List.of(hook),GlobalOpenTelemetry.getTracer("test"),new HookPolicy(1,1,1,200,50))) {
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<6;i++)futures.add(callers.submit(()->assertThrows(AgentException.class,()->dispatcher.fire(event())).code()));
            var codes=new ArrayList<String>();for(var future:futures)codes.add(future.get(3,TimeUnit.SECONDS));
            assertEquals(1,entered.get());assertTrue(codes.contains("HOOK_TIMEOUT"));assertTrue(codes.contains("HOOK_CAPACITY_EXCEEDED"));
            assertEquals("HOOK_TIMEOUT",assertThrows(AgentException.class,()->dispatcher.fire(event())).code());assertEquals(1,entered.get());
        } finally {release.countDown();callers.shutdownNow();}
    }
    @Test void timedOutObserverCannotConsumeGateCapacityOrRejectAnOperation() {
        var release=new CountDownLatch(1);var approved=new AtomicInteger();
        var observer=HarnessLifecycleTest.hook("observer",0,AgentHook.Mode.OBSERVER,Set.of(HookPoint.PRE_MODEL),e->{while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}});
        var gate=HarnessLifecycleTest.hook("gate",1,AgentHook.Mode.GATE,Set.of(HookPoint.PRE_MODEL),e->approved.incrementAndGet());
        try(var dispatcher=new HookDispatcher(List.of(observer,gate),GlobalOpenTelemetry.getTracer("test"),new HookPolicy(1,1,1,500,20))) {
            assertDoesNotThrow(()->dispatcher.fire(event()));assertDoesNotThrow(()->dispatcher.fire(event()));
            assertEquals(2,approved.get());assertEquals(2,dispatcher.observerFailureCount());
        } finally {release.countDown();}
    }
}
