package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FrozenContextLimitsTest {
    @Test void preparationAndLoopProjectionUseCapturedLimitsRatherThanNewClientDefaults() {
        var json=new ObjectMapper().findAndRegisterModules();
        var config=new DeepSeekProperties(URI.create("http://127.0.0.1:1"),"fixture","fixture","fixture",Duration.ofSeconds(1),384,3072);
        var client=new DeepSeekClient(config,json,new ContextPolicy(1024,0));
        var context=new ExecutionPolicySnapshot.Context(98304,16384,4,8192,"1.0","chat_native_v1","slots-v1");
        var control=new CallControl(Instant.now().plusSeconds(30),new CancellationToken()).withContextPolicy(context);
        var input=Map.of("query","x".repeat(4000));
        assertThrows(AgentException.class,()->client.messages("instruction",input));
        assertDoesNotThrow(()->client.messages("instruction",input,control));
        var model=new AgentTypes.ModelContext("x".repeat(4000),LocalDate.now(),new AgentTypes.AccessScope("demo","alice",Set.of()),Set.of(),List.of(),List.of(),3,false,"");
        assertThrows(AgentException.class,()->client.harnessMessages("instruction",model));
        assertDoesNotThrow(()->client.harnessMessages("instruction",model,control));
    }
}
