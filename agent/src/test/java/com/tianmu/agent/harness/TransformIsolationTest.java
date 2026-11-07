package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.hook.*;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransformIsolationTest {
    @Test void lateTransformNeverPublishesOrRunsFollowingHooksAndKeepsItsWorker() throws Exception {
        var release=new CountDownLatch(1);var finished=new CountDownLatch(1);var entered=new AtomicInteger();var following=new AtomicInteger();
        var slow=TransformHookTest.hook("blocked",TransformPoint.INPUT,0,(value,context)->{entered.incrementAndGet();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}finished.countDown();return "late result";});
        var next=TransformHookTest.hook("following",TransformPoint.INPUT,1,(value,context)->{following.incrementAndGet();return value;});
        var context=new TransformContext("1",UUID.randomUUID(),"",0);
        try(var dispatcher=new TransformDispatcher(List.of(slow,next),GlobalOpenTelemetry.getTracer("test"),new ObjectMapper(),new HookPolicy(1,1,1,100,100,1,50))) {
            for(int i=0;i<2;i++)assertEquals("HOOK_TIMEOUT",assertThrows(AgentException.class,()->dispatcher.apply(TransformPoint.INPUT,"original",context,()->{})).code());
            assertEquals(1,entered.get());assertEquals(0,following.get());release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));assertEquals(0,following.get());
        } finally {release.countDown();}
    }
}
