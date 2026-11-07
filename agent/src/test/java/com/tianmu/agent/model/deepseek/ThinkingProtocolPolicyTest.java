package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.prompt.PromptCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ThinkingProtocolPolicyTest {
    DeepSeekProperties properties(String effort,int tokens){return new Binder(new MapConfigurationPropertySource(Map.of("agent.models.api-key","fixture","agent.models.analysis-thinking-effort",effort,"agent.models.analysis-max-tokens",tokens)))
            .bind("agent.models",Bindable.of(DeepSeekProperties.class)).get();}
    @Test void defaultsKeepThinkingEnabledWithSeparateMainAndAnalysisEfforts(){
        var p=properties("low",16384);assertTrue(p.thinkingEnabled());assertEquals("high",p.thinkingEffort());assertEquals("low",p.analysisThinkingEffort());assertEquals(16384,p.analysisMaxTokens());
        assertThrows(BindException.class,()->properties("none",16384));assertThrows(BindException.class,()->properties("low",32769));assertThrows(BindException.class,()->properties("low",8191));
    }
    @Test void changingAnalysisEffortOrOutputBudgetInvalidatesBothReuseProtocols(){
        var json=new ObjectMapper();var prompts=new PromptCatalog();
        var low=properties("low",16384);var high=properties("high",16384);var larger=properties("low",32768);
        var a=new DeepSeekFeedbackModels(new DeepSeekClient(low,json),low,json,prompts);
        for(var p:java.util.List.of(high,larger)){
            var changed=new DeepSeekFeedbackModels(new DeepSeekClient(p,json),p,json,prompts);
            assertNotEquals(a.policyHash(),changed.policyHash());assertNotEquals(a.pipelinePolicyHash(),changed.pipelinePolicyHash());
        }
    }
}
