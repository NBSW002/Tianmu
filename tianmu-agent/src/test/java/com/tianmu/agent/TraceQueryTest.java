package com.tianmu.agent;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.api.*;
import com.tianmu.agent.application.TraceQueryService;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.harness.trace.TraceEnvelope;
import com.tianmu.agent.query.TraceArchive;
import com.tianmu.agent.runtime.store.RunRepository;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TraceQueryTest {
    static final String TRACE = "1234567890abcdef1234567890abcdef";
    final UUID run = UUID.randomUUID();
    final AccessScope caller = new AccessScope("demo", "alice", Set.of(1001L));
    final IdentityPort identity = mock(IdentityPort.class);
    final RunRepository runs = mock(RunRepository.class);
    final TraceArchive archive = mock(TraceArchive.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        when(identity.authenticate("valid")).thenReturn(caller);
        when(identity.forHistoricalRun(caller, run)).thenReturn(caller);
        mvc = MockMvcBuilders.standaloneSetup(new TraceQueryController(new TraceQueryService(identity, runs, archive)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    TraceEnvelope span(UUID id, String actor) {
        return new TraceEnvelope("1.1", UUID.randomUUID().toString(), "agent", "test", TRACE,
                "1234567890abcdef", "0000000000000000", "agent.run", "INTERNAL", 1, 2, 1, "OK",
                Map.of("run.id", id.toString(), "tenant.id", "demo", "actor.id", actor));
    }
    @Test void authorizedLookupRechecksRunAndDisablesCaching() throws Exception {
        when(archive.find(TRACE)).thenReturn(List.of(span(run, "alice")));
        mvc.perform(get("/api/agent/traces/" + TRACE).header("Authorization", "Bearer valid"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].traceId").value(TRACE));
        verify(runs).load(run, caller);
    }
    @Test void unauthenticatedAndMalformedIdsNeverReadArchive() throws Exception {
        mvc.perform(get("/api/agent/traces/" + TRACE)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/traces/not-an-id").header("Authorization", "Bearer valid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(archive);
    }
    @Test void anotherActorAndUnknownTraceDoNotExposeData() throws Exception {
        when(archive.find(TRACE)).thenReturn(List.of(span(run, "bob")), List.of());
        for (int i = 0; i < 2; i++) mvc.perform(get("/api/agent/traces/" + TRACE).header("Authorization", "Bearer valid"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRACE_NOT_FOUND"));
        verifyNoInteractions(runs);
    }
    @Test void revokedResourcePermissionAndForeignRunFailClosed() throws Exception {
        when(archive.find(TRACE)).thenReturn(List.of(span(run, "alice")));
        when(identity.forHistoricalRun(caller, run)).thenThrow(new AgentException("FORBIDDEN", "Revoked"));
        mvc.perform(get("/api/agent/traces/" + TRACE).header("Authorization", "Bearer valid")).andExpect(status().isForbidden());
        doReturn(caller).when(identity).forHistoricalRun(caller, run);
        when(runs.load(run, caller)).thenThrow(new AgentException("RUN_NOT_FOUND", "Missing"));
        mvc.perform(get("/api/agent/traces/" + TRACE).header("Authorization", "Bearer valid")).andExpect(status().isNotFound());
    }
    @Test void archiveFailureIsNotPresentedAsAnEmptySuccess() throws Exception {
        when(archive.find(TRACE)).thenThrow(new AgentException("TRACE_UNAVAILABLE", "Unavailable"));
        mvc.perform(get("/api/agent/traces/" + TRACE).header("Authorization", "Bearer valid"))
                .andExpect(status().isServiceUnavailable());
    }
    @Test void everyRunInTheTraceMustBeAccessible() throws Exception {
        UUID foreign = UUID.randomUUID();
        when(archive.find(TRACE)).thenReturn(List.of(span(run, "alice"), span(foreign, "alice")));
        when(identity.forHistoricalRun(caller, foreign)).thenThrow(new AgentException("RUN_NOT_FOUND", "Missing"));
        mvc.perform(get("/api/agent/traces/" + TRACE).header("Authorization", "Bearer valid"))
                .andExpect(status().isNotFound());
        verify(identity).forHistoricalRun(caller, foreign);
    }
}
