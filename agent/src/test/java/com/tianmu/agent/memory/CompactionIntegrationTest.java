package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.adapters.storage.MysqlRunRepository;
import com.tianmu.agent.runtime.RunState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="AGENT_MEMORY_IT",matches="true")
@ActiveProfiles("demo")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties={"agent.memory.enabled=true","agent.compaction.enabled=true","agent.compaction.trigger-bytes=512","agent.storage.mode=mysql","agent.storage.object-store=file","agent.storage.object-directory=./target/test-artifacts/compaction","agent.storage.redis.enabled=false","harness.trace.directory=./target/test-traces/compaction"})
class CompactionIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){String url=System.getenv("AGENT_MEMORY_JDBC_URL");if(url==null || !url.matches("jdbc:mysql://[^/]+/[^?]*_it(?:\\?.*)?"))throw new IllegalArgumentException("Dedicated _it DB required");r.add("agent.storage.jdbc-url",()->url);r.add("agent.storage.username",()->System.getenv("AGENT_MEMORY_DB_USER"));r.add("agent.storage.password",()->System.getenv().getOrDefault("AGENT_MEMORY_DB_PASSWORD",""));}
    @Autowired MysqlRunRepository runs;
    @Autowired CompactionService compaction;
    @Autowired MemoryBackground background;
    @Autowired MemoryLedger ledger;
    @Autowired PersonalMemoryStore memories;
    @Autowired ObjectMapper json;
    @Autowired DataSource source;
    @MockBean MemoryModelPort model;
    // These storage fixtures own start/commit; the production recovery scanner must not race them.
    @MockBean com.tianmu.agent.runtime.RunService automaticRecovery;
    AccessScope owner;UUID session;JdbcTemplate db;
    @BeforeEach void setup(){owner=new AccessScope("compact_"+UUID.randomUUID().toString().substring(0,12),"alice",Set.of());db=new JdbcTemplate(source);when(model.version()).thenReturn("fixture-compact-v1");when(model.summarize(anyString(),anyMap(),anyInt(),any())).thenAnswer(inv->{var data=inv.<Map<String,Object>>getArgument(1);@SuppressWarnings("unchecked")var messages=(List<MemoryTypes.Message>)data.get("messages");var result=json.createObjectNode();result.put("synopsis","讨论界面与记忆体系的改造");result.putArray("claims").addObject().put("type","rejected_option").put("text","用户要求避免遮挡视频").putArray("source_refs").add(messages.get(0).id().toString());result.putArray("pending_work").add("继续完善方案");return result;});}
    RunState next(){var run=runs.create("继续讨论界面布局，要求不要遮挡视频。".repeat(20),owner,session,UUID.randomUUID().toString(),null).run();session=run.sessionId;assertTrue(run.start());compaction.begin(run);return run;}
    void history(){for(int i=0;i<6;i++){var run=next();run.succeed(new Answer("这一轮已记录界面讨论结果，操作仍等待明确确认。".repeat(10),List.of()));}}
    void awaitJobs()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<until){runs.deliverOutbox();background.tick();if(db.queryForObject("SELECT COUNT(*) FROM agent_memory_projection WHERE tenant_id=? AND status='READY'",Integer.class,owner.tenantId())>0)return;Thread.sleep(20);}fail(db.queryForList("SELECT state,error_code FROM agent_memory_job WHERE tenant_id=?",owner.tenantId()).toString());}
    @AfterEach void cleanup(){if(session!=null)try{runs.deleteSession(session,owner);}catch(AgentException e){if(!e.code().equals("SESSION_NOT_FOUND"))throw e;}runs.garbageCollect();for(String table:List.of("projection_member","projection","job","run","keyword","dependency","revision","source","item","mutation","tombstone","topic","account"))db.update("DELETE FROM agent_memory_"+table+" WHERE tenant_id=? AND owner_user_id=?",owner.tenantId(),owner.userId());}
    @Test void durableBackgroundSummaryEntersItsOwnSlotAndForgetBlocksFinalDelivery()throws Exception{
        history();awaitJobs();var run=next();run.memory(runs.memory(run));compaction.apply(run);
        assertFalse(run.memory().compaction().isEmpty());assertTrue(run.memory().compaction().get(0).throughSequence()<run.memory().recent().get(0).sequence());
        var context=new ModelContext(run.message,run.businessDate,owner,Set.of(),List.of(),List.of(),8,false,"",null,null,run.memory(),List.of());
        var messages=new com.tianmu.agent.model.HarnessContextBuilder(json,com.tianmu.agent.model.ContextPolicy.defaults()).build("rules",context);
        assertTrue(messages.stream().anyMatch(m->"user".equals(m.get("role")) && m.get("content").toString().startsWith("<compaction>")));
        assertTrue(messages.stream().filter(m->"system".equals(m.get("role"))).noneMatch(m->m.get("content").toString().contains("用户要求避免遮挡视频")));
        var remembered=run.memory().compaction();compaction.apply(run);assertEquals(remembered,run.memory().compaction());
        memories.reset(owner,"erase-all");
        assertEquals("MEMORY_CONTEXT_INVALIDATED",assertThrows(AgentException.class,run::verifyMemory).code());
        assertEquals("MEMORY_CONTEXT_INVALIDATED",assertThrows(AgentException.class,()->run.succeed(new Answer("不能提交旧摘要",List.of()))).code());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_projection WHERE tenant_id=? AND JSON_LENGTH(content_json)>0",Integer.class,owner.tenantId()));
    }
    @Test void sourceDeletionWhileSummaryIsBeingGeneratedRejectsLatePublication()throws Exception{
        doAnswer(inv->{memories.reset(owner,"during-summary");var result=json.createObjectNode();result.put("synopsis","不应发布");result.putArray("claims");result.putArray("pending_work");return result;}).when(model).summarize(anyString(),anyMap(),anyInt(),any());
        history();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<until){runs.deliverOutbox();background.tick();if(db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE tenant_id=? AND state='CANCELLED'",Integer.class,owner.tenantId())>0)break;Thread.sleep(20);}
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_projection WHERE tenant_id=? AND status='READY'",Integer.class,owner.tenantId()));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE tenant_id=? AND state='CANCELLED'",Integer.class,owner.tenantId())>0);
    }
    @Test void longConversationCatchesUpWithoutLosingTheNewSuffix()throws Exception{
        for(int i=0;i<24;i++){var run=next();run.succeed(new Answer("这是第"+i+"轮的界面决策，尚未发布或修改原始视频。".repeat(10),List.of()));}
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until){runs.deliverOutbox();background.tick();Long through=db.queryForObject("SELECT COALESCE(MAX(through_seq),0) FROM agent_memory_projection WHERE tenant_id=? AND status='READY'",Long.class,owner.tenantId());if(through!=null && through>=44)break;Thread.sleep(20);}
        assertEquals(44,db.queryForObject("SELECT MAX(through_seq) FROM agent_memory_projection WHERE tenant_id=? AND status='READY'",Long.class,owner.tenantId()));
        assertEquals(48,db.queryForObject("SELECT COUNT(*) FROM agent_message WHERE tenant_id=?",Integer.class,owner.tenantId()));
        var run=next();run.memory(runs.memory(run));compaction.apply(run);assertFalse(run.memory().compaction().isEmpty());assertEquals(45,run.memory().recent().get(0).sequence());assertEquals(44,run.memory().compaction().get(run.memory().compaction().size()-1).throughSequence());
    }
    @Test void completionAndTheNextCheckRollBackTogetherAndRetryIdempotently()throws Exception{
        db.execute("DROP TRIGGER IF EXISTS memory_it_compact_check_fault");
        db.execute("CREATE TRIGGER memory_it_compact_check_fault BEFORE INSERT ON agent_outbox FOR EACH ROW BEGIN IF NEW.destination='MEMORY_COMPACT_CHECK' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected completion fault'; END IF; END");
        try{
            history();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<until){runs.deliverOutbox();background.tick();if(db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE tenant_id=? AND state='PENDING' AND error_code='STORAGE_UNAVAILABLE'",Integer.class,owner.tenantId())>0)break;Thread.sleep(20);}
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE tenant_id=? AND state='SUCCEEDED'",Integer.class,owner.tenantId()));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_outbox WHERE tenant_id=? AND destination='MEMORY_COMPACT_CHECK'",Integer.class,owner.tenantId()));
            assertTrue(db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE tenant_id=? AND state='PENDING' AND error_code='STORAGE_UNAVAILABLE'",Integer.class,owner.tenantId())>0);
        }finally{db.execute("DROP TRIGGER IF EXISTS memory_it_compact_check_fault");}
        db.update("UPDATE agent_memory_job SET available_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND state='PENDING'",owner.tenantId());
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until){runs.deliverOutbox();background.tick();if(db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE tenant_id=? AND state='SUCCEEDED'",Integer.class,owner.tenantId())>0)break;Thread.sleep(20);}
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE tenant_id=? AND state='SUCCEEDED'",Integer.class,owner.tenantId())>0);
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM agent_outbox WHERE tenant_id=? AND destination='MEMORY_COMPACT_CHECK'",Integer.class,owner.tenantId())>0);
    }
    @Test void lostWorkerLeasePreventsAnyProviderDispatch()throws Exception{
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);var providerCalls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv->{try{reached.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));inv.<com.tianmu.agent.runtime.control.CallControl>getArgument(3).beforeDispatch();providerCalls.incrementAndGet();var result=json.createObjectNode();result.put("synopsis","不应发出请求");result.putArray("claims");result.putArray("pending_work");return result;}finally{done.countDown();}}).when(model).summarize(anyString(),anyMap(),anyInt(),any());
        history();runs.deliverOutbox();background.tick();assertTrue(reached.await(5,TimeUnit.SECONDS));
        try{db.update("UPDATE agent_memory_job SET state='CANCELLED',lease_token=NULL WHERE tenant_id=? AND state='RUNNING'",owner.tenantId());}finally{release.countDown();}
        assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(0,providerCalls.get());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_projection WHERE tenant_id=?",Integer.class,owner.tenantId()));
    }
}
