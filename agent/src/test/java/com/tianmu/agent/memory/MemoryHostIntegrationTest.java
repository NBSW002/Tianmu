package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.memory.PersonalMemory.*;
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
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="AGENT_MEMORY_IT",matches="true")
@ActiveProfiles("demo")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties={"agent.memory.enabled=true","agent.memory.recall.overview-max-items=3","agent.storage.mode=mysql","agent.storage.object-store=file","agent.storage.object-directory=./target/test-artifacts/host-memory","agent.storage.redis.enabled=false","harness.trace.directory=./target/test-traces/host-memory"})
class MemoryHostIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){MemoryRuntimeIntegrationTest.database(r);}
    @Autowired MemoryHostService host;@Autowired MemoryLedger ledger;@Autowired PersonalMemoryStore store;@Autowired MysqlRunRepository runs;@Autowired DataSource source;
    @Autowired MemoryConsolidation learning;
    @Autowired ObjectMapper json;@Autowired com.tianmu.agent.preparation.PreparationService preparation;@Autowired com.tianmu.agent.application.DeliveryService delivery;
    @Autowired com.tianmu.agent.application.ContextLifecycle contexts;@Autowired com.tianmu.agent.harness.RuntimeHarness harness;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("memoryIndexExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor indexExecutor;
    @MockBean MemoryBackground background;@MockBean com.tianmu.agent.runtime.RunService recovery;@MockBean MemoryModelPort model;
    AccessScope owner;JdbcTemplate db;List<RunState> created;
    @BeforeEach void setup(){owner=new AccessScope("host_"+UUID.randomUUID().toString().substring(0,12),"alice",Set.of());db=new JdbcTemplate(source);created=new ArrayList<>();org.mockito.Mockito.when(model.version()).thenReturn("host-memory-fixture-v1");}
    RunState run(String message,UUID session){var r=runs.create(message,owner,session,UUID.randomUUID().toString(),null).run();r.start();learning.observe(db,owner,r.sessionId,r.id);ledger.begin(r);created.add(r);return r;}
    PersonalMemory.Receipt add(String text){return store.create(owner,new Content(Scope.user(),PersonalMemory.Kind.PROFILE,text,null),UUID.randomUUID().toString());}
    void prior(RunState next,RunState previous){next.memory(next.memory().withRecent(List.of(new MemoryTypes.Message(UUID.randomUUID(),previous.id,1,"assistant","已显示上一页",java.time.Instant.now()))));}
    @AfterEach void cleanup(){for(var r:created)try{runs.deleteSession(r.sessionId,owner);}catch(AgentException ignored){}for(var t:List.of("embedding","recall_manifest","index","dream","candidate","fact","window","cursor","observation","job","run","keyword","dependency","revision","source","item","mutation","tombstone","topic","account"))db.update("DELETE FROM agent_memory_"+t+" WHERE tenant_id=? AND owner_user_id=?",owner.tenantId(),owner.userId());}
    @Test void overviewReadsFactsWithoutAnyModelOrToolAndProtectsSourceDependencies(){
        add("用户来自天津大学。");add("用户是一名学生。");var r=run("你了解我吗",null);var answer=host.answer(r).orElseThrow();
        assertTrue(answer.text().contains("用户来自天津大学。"));assertFalse(answer.text().contains("天津大学的学生"));assertEquals(0,r.snapshot().modelCalls());assertTrue(r.execution().exchanges().isEmpty());assertEquals(2,ledger.boundary(owner,r.id).refs().size());
        var current=store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0);store.delete(owner,current.id(),current.version(),UUID.randomUUID().toString());assertThrows(AgentException.class,()->ledger.guard(r));
    }
    @Test void pagingAcrossTurnsHasNoDuplicatesAndOrdinaryContinueIsNotCaptured(){
        for(int i=0;i<7;i++)add("用户的事实编号"+i);var first=run("查看我的记忆",null);var page=host.answer(first).orElseThrow();assertTrue(page.text().contains("继续查看"));
        var refs=new HashSet<UUID>();ledger.boundary(owner,first.id).refs().forEach(x->refs.add(x.id()));first.succeed(page);
        var second=run("继续查看",first.sessionId);prior(second,first);assertTrue(host.handles(second));var p2=host.answer(second).orElseThrow();ledger.boundary(owner,second.id).refs().forEach(x->assertTrue(refs.add(x.id())));assertEquals(6,refs.size());second.succeed(p2);
        var third=run("下一页",first.sessionId);prior(third,second);var p3=host.answer(third).orElseThrow();assertFalse(p3.text().contains("继续查看"));assertEquals(1,ledger.boundary(owner,third.id).refs().size());
        var unrelated=run("继续",null);assertFalse(host.handles(unrelated));assertTrue(host.answer(unrelated).isEmpty());
    }
    @Test void accountChangesResetPaginationInsteadOfReusingOldSnapshot(){
        for(int i=0;i<4;i++)add("旧事实"+i);var a=run("你了解我吗",null);var answer=host.answer(a).orElseThrow();a.succeed(answer);add("新的事实");var b=run("继续查看",a.sessionId);prior(b,a);assertTrue(host.answer(b).orElseThrow().text().contains("记忆已发生变化"));
    }
    @Test void unrelatedOwnerCannotReadManifestAndResetErasesIt(){
        add("私人信息");var a=run("你了解我吗",null);host.answer(a).orElseThrow();var other=new RunState(new AccessScope(owner.tenantId(),"bob",Set.of()),"你了解我吗");assertTrue(ledger.recallManifest(other,a.id).isEmpty());
        store.reset(owner,UUID.randomUUID().toString());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_recall_manifest WHERE tenant_id=?",Integer.class,owner.tenantId()));
    }
    @Test void disabledMemoryDoesNotExposePrivateText(){
        add("不可展示的事实");var s=store.settings(owner);store.settings(owner,s.version(),Mode.OFF);var r=run("你了解我吗",null);assertTrue(host.answer(r).orElseThrow().text().contains("已关闭"));assertTrue(ledger.boundary(owner,r.id).refs().isEmpty());
    }
    @Test void actualPreparationAndDeliveryUseDirectHostPath(){
        add("用户来自天津大学。");var r=run("我的信息你知道多少",null);var prepared=preparation.prepare(r,r.message);
        assertEquals(com.tianmu.agent.core.PreparationTypes.Route.DIRECT,prepared.route());r.prepared(prepared);delivery.acknowledgement(r,prepared.reply());
        assertEquals(Status.SUCCEEDED,r.snapshot().status());assertEquals(0,r.snapshot().modelCalls());assertEquals(0,r.snapshot().preparationCalls());
        assertEquals(0,db.queryForObject("SELECT eligible FROM agent_memory_observation WHERE tenant_id=? AND run_id=?",Integer.class,owner.tenantId(),r.id.toString()),"A memory overview must not schedule another extraction model");
    }
    @Test void deletionIntentOnlyGuidesToManagementAndCannotTriggerBackgroundMutation(){
        var item=add("用户来自天津大学。");var r=run("请忘记我的学校",null);var prepared=preparation.prepare(r,r.message);assertEquals(com.tianmu.agent.core.PreparationTypes.Route.DIRECT,prepared.route());
        assertTrue(prepared.reply().text().contains("记忆管理"));assertEquals(1,store.get(owner,item.id()).version());assertEquals(0,db.queryForObject("SELECT eligible FROM agent_memory_observation WHERE tenant_id=? AND run_id=?",Integer.class,owner.tenantId(),r.id.toString()));
    }
    MemorySemanticIndex index(MemoryEmbedding embedding){
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();factory.addBean("embedding",embedding);
        return new MemorySemanticIndex(source,factory.getBeanProvider(MemoryEmbedding.class),json,ledger,new MemorySemanticPolicy(5000,20,800,60,20,0.9,30),indexExecutor);
    }
    MemoryEmbedding model(java.util.function.BiFunction<List<String>,com.tianmu.agent.runtime.control.CallControl,List<List<Float>>> fn){
        return new MemoryEmbedding(){public String version(){return "fixture-semantic-v1";}public int dimension(){return 2;}public List<List<Float>> embed(List<String> texts,com.tianmu.agent.runtime.control.CallControl c){return fn.apply(texts,c);}};
    }
    @Test void semanticIndexRecallsParaphrasesAndDoesNotLeakAcrossOwners(){
        add("我喜欢通过具体例子学习。");var calls=new java.util.concurrent.atomic.AtomicInteger();
        var index=index(model((texts,c)->{c.beforeDispatch();calls.incrementAndGet();return List.of(List.of(1f,0f));}));index.index();
        var r=run("请用一个生活场景讲解",null);assertEquals(1,index.search(r,r.message,ledger.boundary(owner,r.id).sequence()).size());assertEquals(2,calls.get());
        var otherOwner=new AccessScope(owner.tenantId(),"bob",Set.of());var other=runs.create("举个例子",otherOwner,null,UUID.randomUUID().toString(),null).run();other.start();ledger.begin(other);
        try{assertTrue(index.search(other,other.message,ledger.boundary(otherOwner,other.id).sequence()).isEmpty());assertEquals(2,calls.get(),"An empty index must not dispatch a query embedding");}
        finally{runs.deleteSession(other.sessionId,otherOwner);for(var t:List.of("run","account"))db.update("DELETE FROM agent_memory_"+t+" WHERE tenant_id=? AND owner_user_id=?",owner.tenantId(),"bob");}
    }
    @Test void localSemanticTimeoutDegradesWithoutFailingTheRun(){
        add("可读取的个人事实");var index=index(model((texts,c)->{c.beforeDispatch();return List.of(List.of(1f,0f));}));index.index();
        var failed=index(model((texts,c)->{throw new AgentException("DEADLINE_EXCEEDED","Local embedding deadline");}));var r=run("用我的偏好回答",null);
        assertTrue(failed.search(r,r.message,ledger.boundary(owner,r.id).sequence()).isEmpty());assertEquals(Status.RUNNING,r.snapshot().status());
    }
    @Test void deletionBetweenSelectionAndDispatchPreventsEmbeddingAndResurrection(){
        var item=add("这条内容必须在外发前重新授权。");var sent=new java.util.concurrent.atomic.AtomicInteger();
        var index=index(model((texts,c)->{store.delete(owner,item.id(),item.version(),UUID.randomUUID().toString());c.beforeDispatch();sent.incrementAndGet();return List.of(List.of(1f,0f));}));
        index.index();assertEquals(0,sent.get());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_embedding WHERE tenant_id=?",Integer.class,owner.tenantId()));
    }
    @Test void consentChangedDuringProviderIoCannotPublishOldVectorOrFailure(){
        add("该事实的向量任务不能跨授权版本发布。");var index=index(model((texts,c)->{
            c.beforeDispatch();var s=store.settings(owner);store.settings(owner,s.version(),Mode.OFF);s=store.settings(owner);store.settings(owner,s.version(),Mode.ON);return List.of(List.of(1f,0f));
        }));index.index();assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_embedding WHERE tenant_id=?",Integer.class,owner.tenantId()));
    }
    @Test void finalEscapedBodyAlwaysFitsTheDeliveryLimit(){
        for(int i=0;i<20;i++)add("事实"+i+"_".repeat(900));var expanded=new MemoryHostService(ledger,new MemoryRecallPolicy(20,32768,200,30000),harness);var r=run("你了解我吗",null);
        var answer=expanded.answer(r).orElseThrow();assertTrue(answer.text().length()<=8000);assertTrue(answer.text().contains("继续查看"));delivery.acknowledgement(r,answer);assertEquals(Status.SUCCEEDED,r.snapshot().status());
    }
    @Test void ordinaryContinueInheritsDependenciesBeforeAnyLaterRevocation(){
        var item=add("只能在来源有效时使用的事实");var first=run("你了解我吗",null);var prepared=preparation.prepare(first,first.message);first.prepared(prepared);delivery.acknowledgement(first,prepared.reply());
        var next=run("继续",first.sessionId);contexts.load(next);assertFalse(host.handles(next));assertFalse(ledger.boundary(owner,next.id).refs().isEmpty());
        store.delete(owner,item.id(),item.version(),UUID.randomUUID().toString());assertThrows(AgentException.class,()->ledger.guard(next));
    }
}
