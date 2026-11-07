package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeModelCatalogTest {
    @TempDir Path directory;
    ObjectMapper json=new ObjectMapper();
    DeepSeekProperties defaults=new DeepSeekProperties(URI.create("https://api.deepseek.com"),"secret","deepseek-chat","deepseek-chat",Duration.ofSeconds(2),384,1024);
    Path file(){return directory.resolve("models.json");}
    void write(String model) throws Exception {json.writeValue(file().toFile(),new RuntimeModelCatalog.Document("fast",List.of(new RuntimeModelCatalog.Entry("fast","Fast","https://example.com/v1",model,"PROVIDER_KEY",1024,true))));}
    RuntimeModelCatalog catalog(){return new RuntimeModelCatalog(defaults,json,ContextPolicy.defaults(),new PromptCatalog(),null,file().toString(),key->"test-secret");}
    @Test void thinkingProtocolIsPartOfBothStartupAndCatalogRevisions() throws Exception {
        write("deepseek-flash");var enabled=catalog();
        var disabled=new DeepSeekProperties(defaults.baseUrl(),defaults.apiKey(),defaults.preparationModel(),defaults.harnessModel(),defaults.connectTimeout(),defaults.preparationMaxTokens(),defaults.harnessMaxTokens(),defaults.analysisModel(),false);
        var previous=new RuntimeModelCatalog(disabled,json,ContextPolicy.defaults(),new PromptCatalog(),null,file().toString(),key->"test-secret");
        assertNotEquals(enabled.select("fast").choice().revision(),previous.select("fast").choice().revision());
        assertEquals("MODEL_REVISION_UNAVAILABLE",assertThrows(AgentException.class,()->previous.restore(enabled.select("fast").choice())).code());
        var on=new RuntimeModelCatalog(defaults,json,ContextPolicy.defaults(),new PromptCatalog(),null,"",key->"test-secret");
        var off=new RuntimeModelCatalog(disabled,json,ContextPolicy.defaults(),new PromptCatalog(),null,"",key->"test-secret");
        assertNotEquals(on.select(null).choice().revision(),off.select(null).choice().revision());
    }
    @Test void atomicReloadPinsExistingTasksAndNewRequestsUseNewRevision() throws Exception {
        write("model-one");var catalog=catalog();var old=catalog.select("fast");write("model-two");var next=catalog.select("fast");
        assertEquals("model-one",old.model().modelName());assertEquals("model-two",next.model().modelName());assertNotEquals(old.choice().revision(),next.choice().revision());
        assertSame(old,catalog.restore(old.choice()));
        var run=new com.tianmu.agent.runtime.RunState(new AccessScope("t","u",Set.of()),"hello");run.selectModel(old);
        assertSame(old.model(),run.modelOr(next.model()));assertThrows(IllegalStateException.class,()->run.selectModel(next));
        assertFalse(json.writeValueAsString(catalog.choices()).contains("secret"));assertFalse(json.writeValueAsString(catalog.choices()).contains("example.com"));
    }
    @Test void mainThinkingEffortChangeCannotRestoreThePriorPinnedCatalog() throws Exception {
        write("deepseek-flash");var high=catalog();var selected=high.select("fast").choice();
        var low=new DeepSeekProperties(defaults.baseUrl(),defaults.apiKey(),defaults.preparationModel(),defaults.harnessModel(),defaults.connectTimeout(),defaults.preparationMaxTokens(),defaults.harnessMaxTokens(),defaults.analysisModel(),true,"low",defaults.analysisThinkingEffort(),defaults.analysisMaxTokens());
        var changed=new RuntimeModelCatalog(low,json,ContextPolicy.defaults(),new PromptCatalog(),null,file().toString(),key->"test-secret");
        assertNotEquals(selected.revision(),changed.select("fast").choice().revision());
        assertEquals("MODEL_REVISION_UNAVAILABLE",assertThrows(AgentException.class,()->changed.restore(selected)).code());
    }
    @Test void invalidCatalogNeverPublishesAndRestartCannotSilentlyChangePinnedModel() throws Exception {
        write("model-one");var catalog=catalog();var old=catalog.select("fast");Files.writeString(file(),"{invalid");
        assertEquals("MODEL_CATALOG_INVALID",assertThrows(AgentException.class,catalog::reload).code());assertSame(old,catalog.restore(old.choice()));
        write("model-two");var restarted=catalog();assertEquals("MODEL_REVISION_UNAVAILABLE",assertThrows(AgentException.class,()->restarted.restore(old.choice())).code());
        assertEquals("MODEL_NOT_AVAILABLE",assertThrows(AgentException.class,()->restarted.select("disabled")).code());
    }
    @Test void idempotencyIncludesModelButDoesNotReplacePinnedRevisionOnRetry() throws Exception {
        write("model-one");var catalog=catalog();var old=catalog.select("fast").choice();var scope=new AccessScope("t","u",Set.of());var repo=new MemoryRunRepository();
        var created=repo.create("hello",scope,null,"request",null,null,old);write("model-two");var next=catalog.select("fast").choice();
        var duplicate=repo.create("hello",scope,null,"request",null,null,next);assertFalse(duplicate.fresh());assertEquals(old,duplicate.run().modelChoice());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AgentException.class,()->repo.create("hello",scope,null,"request",null,null,new RuntimeModelCatalog.Choice("other","Other","other","v1"))).code());
    }
}
