package com.tianmu.agent.harness;

import com.tianmu.agent.runtime.store.RunRepository;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"agent.policy-snapshot.enabled=true","harness.trace.directory=./target/test-traces/policy-snapshot"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PolicySnapshotSpringTest {
    @Autowired RunRepository repository;
    @Test void actualProductionWiringCapturesOnlyFreshRunsAndKeepsIdempotentPolicies() {
        var owner=new AccessScope("policy","alice",Set.of(1001L));String key=UUID.randomUUID().toString();
        var first=repository.create("知识咨询",owner,null,key,null,null);var fixed=first.run().executionPolicy();
        assertNotNull(fixed);fixed.verify();assertFalse(fixed.business().resources().isEmpty());
        assertEquals(fixed.foreground().tokenLimit(),first.run().modelBudget().snapshot().limit());
        assertEquals(fixed.foreground().tokenReservation(),first.run().modelBudget().snapshot().reservation());
        var duplicate=repository.create("知识咨询",owner,null,key,null,null);assertFalse(duplicate.fresh());assertEquals(fixed,duplicate.run().executionPolicy());
        first.run().cancel();assertEquals(fixed,first.run().snapshot().executionPolicy());
    }
}
