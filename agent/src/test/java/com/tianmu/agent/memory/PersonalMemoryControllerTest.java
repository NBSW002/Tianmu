package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.api.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.memory.PersonalMemory.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PersonalMemoryControllerTest {
    IdentityPort identity;
    PersonalMemoryStore store;
    MockMvc mvc;
    AccessScope owner=new AccessScope("tenant","alice",Set.of());
    static final String BODY="{\"expectedVersion\":0,\"content\":{\"scope\":{\"type\":\"USER\",\"key\":\"self\"},\"kind\":\"PREFERENCE\",\"text\":\"简短回答\"}}";
    @BeforeEach void setup() {
        identity=mock(IdentityPort.class);store=mock(PersonalMemoryStore.class);
        when(identity.authenticate("token")).thenReturn(owner);
        var json=new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc=MockMvcBuilders.standaloneSetup(new PersonalMemoryController(identity,store)).setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    @Test void missingAuthenticationCannotReachStorage() throws Exception {
        mvc.perform(get("/api/agent/memories")).andExpect(status().isUnauthorized());verifyNoInteractions(store);
    }
    @Test void strictNestedInputsRejectForgedSourcesWithoutCallingStorage() throws Exception {
        for(String body:List.of(BODY.replace("\"expectedVersion\":0","\"sessionId\":\"fake\",\"expectedVersion\":0"),
                BODY.replace("\"kind\":","\"source\":\"SYSTEM\",\"kind\":"),
                BODY.replace("\"key\":\"self\"","\"key\":\"self\",\"owner\":\"bob\""))) {
            mvc.perform(post("/api/agent/memories").header("Authorization","Bearer token").header("Idempotency-Key","save").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(store);
    }
    @Test void createUsesAuthenticatedOwnerAndNoStoreResponse() throws Exception {
        UUID id=UUID.randomUUID();when(store.create(eq(owner),any(),eq("save"))).thenReturn(new Receipt(id,1,1,"CREATED"));
        mvc.perform(post("/api/agent/memories").header("Authorization","Bearer token").header("Idempotency-Key","save").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.content").doesNotExist());
        verify(store).create(eq(owner),argThat(c->c.scope().equals(Scope.user()) && c.text().equals("简短回答")),eq("save"));
    }
    @Test void videoReadsReauthenticateResourceBeforeReturningBody() throws Exception {
        UUID id=UUID.randomUUID();when(store.scope(owner,id)).thenReturn(new Scope(ScopeType.VIDEO,"1001"));
        when(identity.forCreate(eq("token"),eq(""),isNull(),any(),isNull())).thenThrow(new AgentException("FORBIDDEN","Revoked"));
        mvc.perform(get("/api/agent/memories/"+id).header("Authorization","Bearer token")).andExpect(status().isForbidden());
        verify(store,never()).get(any(),any());
    }
    @Test void ownershipAndCasHaveStableHttpErrors() throws Exception {
        UUID id=UUID.randomUUID();when(store.scope(owner,id)).thenThrow(PersonalMemory.missing());
        mvc.perform(get("/api/agent/memories/"+id).header("Authorization","Bearer token")).andExpect(status().isNotFound());
        when(store.settings(eq(owner),eq(2L),eq(Mode.OFF))).thenThrow(PersonalMemory.conflict());
        mvc.perform(patch("/api/agent/memory-settings").header("Authorization","Bearer token").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2,\"mode\":\"OFF\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMORY_VERSION_CONFLICT"));
    }
    @Test void sessionScopeIsReservedUntilItsOwnSourceLifecycleIsImplemented()throws Exception{
        String session=UUID.randomUUID().toString();
        mvc.perform(post("/api/agent/memories").header("Authorization","Bearer token").header("Idempotency-Key","save-session").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"type\":\"USER\",\"key\":\"self\"","\"type\":\"SESSION\",\"key\":\""+session+"\""))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/agent/memories").header("Authorization","Bearer token").param("scopeType","SESSION").param("scopeKey",session)).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
}
