package com.tianmu.agent.memory;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.RunState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"agent.memory.enabled=false","agent.compaction.enabled=true","agent.compaction.trigger-bytes=512","agent.storage.mode=mysql","agent.storage.object-store=file","agent.storage.object-directory=./target/test-artifacts/compaction-only","agent.storage.redis.enabled=false","harness.trace.directory=./target/test-traces/compaction-only"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="AGENT_MEMORY_IT",matches="true")
class CompactionOnlyIntegrationTest extends CompactionIntegrationTest {
    @Autowired com.tianmu.agent.preparation.PreparationService preparation;
    @Autowired com.tianmu.agent.tool.ToolDispatcher dispatcher;
    @org.springframework.boot.test.mock.mockito.MockBean com.tianmu.agent.preparation.FaqPort faq;
    @org.junit.jupiter.api.BeforeEach void noTenantSpecificFaq(){org.mockito.Mockito.when(faq.find(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());}
    RunState producer()throws Exception{history();awaitJobs();var run=next();run.memory(runs.memory(run));compaction.apply(run);assertFalse(run.memory().compaction().isEmpty());run.succeed(new Answer("承接摘要中的设计决定",List.of()));return run;}
    RunState changedTopic(){var run=runs.create("换个话题，解释什么是缓存",owner,session,UUID.randomUUID().toString(),null).run();assertTrue(run.start());compaction.begin(run);return run;}
    @Test void recentHistoryCarriesSummaryDependenciesWithPersonalMemoryDisabled()throws Exception{
        var previous=producer();var run=changedTopic();preparation.prepare(run,run.message);assertTrue(run.memory().compaction().isEmpty());
        assertFalse(ProjectionChecks.refs(db,json,owner,run.id).isEmpty());run.succeed(new Answer("由前文继续回答",List.of()));
        memories.reset(owner,"forget-context");assertFalse(ledger.historyAllowed(owner,previous.id));assertFalse(ledger.historyAllowed(owner,run.id));
    }
    @Test void readHistoryCarriesSummaryDependenciesWithPersonalMemoryDisabled()throws Exception{
        producer();var run=changedTopic();var result=dispatcher.execute(new ToolCall("read_history",Map.of("query","承接摘要","limit",2)),run);assertFalse(((List<?>)result.data().get("messages")).isEmpty());
        assertFalse(ProjectionChecks.refs(db,json,owner,run.id).isEmpty());run.succeed(new Answer("使用工具读回的回答",List.of()));
        memories.reset(owner,"forget-tool-context");assertFalse(ledger.historyAllowed(owner,run.id));
    }
}
