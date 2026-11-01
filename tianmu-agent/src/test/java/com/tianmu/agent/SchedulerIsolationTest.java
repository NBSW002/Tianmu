package com.tianmu.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.config.ExecutionConfiguration;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis.Blob;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.runtime.store.ObjectStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SchedulerIsolationTest {
    static final class SlowMaintenance {
        final CountDownLatch entered=new CountDownLatch(2), release=new CountDownLatch(1);
        final List<String> threads=new CopyOnWriteArrayList<>();
        void block(){
            threads.add(Thread.currentThread().getName());entered.countDown();
            try{release.await(6,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        // Future annotations without an explicit scheduler must also stay away from the watchdog.
        @Scheduled(fixedDelay=5000) public void unqualifiedMaintenance(){block();}
    }
    @Configuration(proxyBeanMethods=false) @EnableScheduling
    static class MaintenanceConfiguration {
        @Bean SlowMaintenance slowMaintenance(){return new SlowMaintenance();}
        @Bean FeedbackObjects feedbackObjects(SlowMaintenance slow){
            var index=new FeedbackObjectIndex(){
                public void register(UUID job,Blob blob){}
                public List<Expired> expired(int limit){slow.block();return List.of();}
                public void removed(String key){}
            };
            return new FeedbackObjects(mock(ObjectStore.class),new ObjectMapper(),index);
        }
    }
    @Test void saturatedScheduledStorageAndDefaultMaintenanceCannotDelayTheActualWatchdog(){
        new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class,LoopConfiguration.class,MaintenanceConfiguration.class)
                .withBean(MeterRegistry.class,SimpleMeterRegistry::new)
                .withPropertyValues("harness.loop.request-timeout=100ms")
                .run(context->{
                    assertNull(context.getStartupFailure());var slow=context.getBean(SlowMaintenance.class);
                    var run=new RunState(new AccessScope("t","u",Set.of(1001L)),"watchdog isolation");
                    try{
                        assertTrue(slow.entered.await(2,TimeUnit.SECONDS),"Both maintenance workers must be occupied");
                        assertTrue(slow.threads.stream().allMatch(t->t.startsWith("agent-maintenance-")),slow.threads.toString());
                        var signalled=new CountDownLatch(1);var signalThread=new AtomicReference<String>();
                        run.cancellation().onCancel(()->{signalThread.set(Thread.currentThread().getName());signalled.countDown();});
                        context.getBean(LoopControl.class).arm(run);
                        assertTrue(signalled.await(1500,TimeUnit.MILLISECONDS),"Watchdog must signal before slow maintenance is released");
                        assertEquals(1,slow.release.getCount());assertTrue(signalThread.get().startsWith("agent-deadline-"));
                        assertEquals(com.tianmu.agent.core.StopReason.DEADLINE_EXCEEDED,run.cancellation().reason());
                    }finally{slow.release.countDown();run.cancel();}
                });
    }
}
