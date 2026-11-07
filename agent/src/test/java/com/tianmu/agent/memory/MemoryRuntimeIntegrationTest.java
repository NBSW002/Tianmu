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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="AGENT_MEMORY_IT",matches="true")
@ActiveProfiles("demo")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties={"agent.memory.enabled=true","agent.storage.mode=mysql","agent.storage.object-store=file","agent.storage.object-directory=./target/test-artifacts/memory-runtime","agent.storage.redis.enabled=false","harness.trace.directory=./target/test-traces/memory-runtime"})
class MemoryRuntimeIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        String url=System.getenv("AGENT_MEMORY_JDBC_URL");
        if(url==null || !url.matches("jdbc:mysql://[^/]+/[^?]*_it(?:\\?.*)?"))throw new IllegalArgumentException("Dedicated memory _it database required");
        registry.add("agent.storage.jdbc-url",()->url);registry.add("agent.storage.username",()->System.getenv("AGENT_MEMORY_DB_USER"));registry.add("agent.storage.password",()->System.getenv().getOrDefault("AGENT_MEMORY_DB_PASSWORD",""));
    }
    @Autowired MysqlRunRepository runs;
    @Autowired PersonalMemoryStore store;
    @Autowired MemoryLedger ledger;
    @Autowired MemoryBackground background;
    @Autowired DataSource source;
    @Autowired ObjectMapper json;
    @Autowired com.tianmu.agent.harness.trace.TraceDetails traceDetails;
    @MockBean MemoryModelPort model;
    @MockBean com.tianmu.agent.runtime.RunService automaticRecovery;
    @MockBean com.tianmu.agent.query.VideoPagePort videoPages;
    AccessScope owner;
    JdbcTemplate db;
    final List<RunState> created=new ArrayList<>();
    @BeforeEach void setup(){
        owner=new AccessScope("memory_"+UUID.randomUUID().toString().substring(0,12),"alice",Set.of(1001L));db=new JdbcTemplate(source);
        when(model.version()).thenReturn("fixture-memory-v1");
        when(videoPages.page(eq(1001L),any())).thenReturn(new com.tianmu.agent.query.VideoPagePort.Page("1001","测试视频",1000));
        when(model.evaluate(anyString(),anyMap(),anyInt(),any())).thenAnswer(inv->{var result=json.createObjectNode();result.putArray("items").addObject().put("quote",inv.<Map<String,Object>>getArgument(1).get("user_message").toString()).put("kind","PREFERENCE").put("topic","");return result;});
    }
    RunState run(String query){var run=runs.create(query,owner,null,UUID.randomUUID().toString(),null).run();assertTrue(run.start());created.add(run);return run;}
    @AfterEach void cleanup(){
        for(var run:created)try{runs.deleteSession(run.sessionId,owner);}catch(AgentException e){if(!e.code().equals("SESSION_NOT_FOUND"))throw e;}
        runs.garbageCollect();
        for(String table:List.of("job","run","keyword","dependency","revision","source","item","mutation","tombstone","topic","account"))db.update("DELETE FROM agent_memory_"+table+" WHERE tenant_id=? AND owner_user_id=?",owner.tenantId(),owner.userId());
    }
    @Test void explicitSourceDeletionInvalidatesConsumedTurnsAndHistoryInTheSameTransaction(){
        var origin=run("记住，我偏好简短回答");var saved=ledger.declare(origin,new Content(Scope.user(),PersonalMemory.Kind.PREFERENCE,"我偏好简短回答",null),"source",true);origin.succeed(new Answer("已保存",List.of()));
        var consumer=run("解释一下推荐系统");ledger.begin(consumer);var boundary=ledger.boundary(owner,consumer.id);
        var entry=ledger.read(owner,saved.id(),saved.version(),boundary.sequence());ledger.consume(consumer,List.of(entry));
        consumer.succeed(new Answer("这是一条使用过该偏好的回答",List.of()));
        assertTrue(ledger.historyAllowed(owner,consumer.id));
        runs.deleteSession(origin.sessionId,owner);
        assertEquals("MEMORY_NOT_FOUND",assertThrows(AgentException.class,()->store.get(owner,saved.id())).code());
        assertFalse(ledger.historyAllowed(owner,consumer.id));
        assertTrue(runs.messages(consumer.sessionId,owner,Long.MAX_VALUE).stream().filter(m->m.role().equals("assistant")).noneMatch(m->m.text().contains("使用过该偏好")));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_source WHERE tenant_id=? AND declaration<>''",Integer.class,owner.tenantId()));
    }
    @Test void reasoningPayloadRequiresLiveMemoryAuthorityAndRejectsForgottenSource() throws Exception {
        owner=new AccessScope(owner.tenantId(),owner.userId(),owner.videoIds(),UUID.randomUUID().toString());
        var saved=store.create(owner,new Content(new Scope(ScopeType.VIDEO,"1001"),PersonalMemory.Kind.PREFERENCE,"先说明数据来源",null),"reasoning-root");
        var consumer=run("解释一下结果");ledger.begin(consumer);
        ledger.consume(consumer,List.of(ledger.read(owner,saved.id(),1,ledger.boundary(owner,consumer.id).sequence())));
        String reference;
        try(var provider=io.opentelemetry.sdk.trace.SdkTracerProvider.builder().build()){
            var span=provider.get("test").spanBuilder("model.request").startSpan();
            try(var scope=traceDetails.bind(io.opentelemetry.context.Context.root().with(span),consumer.id).makeCurrent()){
                com.tianmu.agent.harness.trace.TraceDetails.reasoning("按偏好先说明数据来源，邮箱 person@example.com");
            }
            span.end();
            reference=((io.opentelemetry.sdk.trace.ReadableSpan)span).toSpanData().getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("detail.reasoning_ref"));
        }
        assertNotNull(reference);var ref=reference.split("/");
        var fresh=new AccessScope(owner.tenantId(),owner.userId(),owner.videoIds(),UUID.randomUUID().toString());
        when(videoPages.page(eq(1001L),any())).thenAnswer(inv->{
            AccessScope scope=inv.getArgument(1);if(!fresh.authorizationRef().equals(scope.authorizationRef()))throw new AgentException("AUTH_CONTEXT_EXPIRED","Old credential reference expired");
            return new com.tianmu.agent.query.VideoPagePort.Page("1001","测试视频",1000);
        });
        var endpoint=new com.tianmu.agent.api.TraceDetailController(credential->fresh,runs,traceDetails);
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("authority",ledger);
        org.springframework.test.util.ReflectionTestUtils.setField(endpoint,"sourceAuthorities",beans.getBeanProvider(com.tianmu.agent.runtime.spi.TraceSourceAuthority.class));
        byte[] payload=endpoint.get(consumer.id,ref[0],ref[1],Long.parseLong(ref[2]),ref[3],"Bearer fixture").getBody();
        assertTrue(traceDetails.requiresSourceGuard(payload));assertTrue(json.readTree(payload).path("value").asText().contains("先说明数据来源"));
        assertFalse(new String(payload,java.nio.charset.StandardCharsets.UTF_8).contains("person@example.com"));
        var missing=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        org.springframework.test.util.ReflectionTestUtils.setField(endpoint,"sourceAuthorities",missing.getBeanProvider(com.tianmu.agent.runtime.spi.TraceSourceAuthority.class));
        assertEquals("MEMORY_SOURCE_UNAVAILABLE",assertThrows(AgentException.class,()->endpoint.get(consumer.id,ref[0],ref[1],Long.parseLong(ref[2]),ref[3],"Bearer fixture")).code());
        org.springframework.test.util.ReflectionTestUtils.setField(endpoint,"sourceAuthorities",beans.getBeanProvider(com.tianmu.agent.runtime.spi.TraceSourceAuthority.class));
        store.delete(owner,saved.id(),1,"forget-reasoning-root");
        assertEquals("MEMORY_CONTEXT_INVALIDATED",assertThrows(AgentException.class,()->endpoint.get(consumer.id,ref[0],ref[1],Long.parseLong(ref[2]),ref[3],"Bearer fixture")).code());
    }
    @Test void naturalRunGcPreservesExplicitDeclarationButDerivedSourceExpiresBeforeGc(){
        var origin=run("记住，我希望先举例");var saved=ledger.declare(origin,new Content(Scope.user(),PersonalMemory.Kind.PREFERENCE,"先举例",null),"explicit",true);origin.succeed(new Answer("好",List.of()));
        db.update("UPDATE agent_run SET data_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),deadline_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=?",origin.id.toString());runs.garbageCollect();
        assertEquals("先举例",store.get(owner,saved.id()).content().text());
        ledger.auto(owner,store.settings(owner).version(),true);var derived=run("我长期喜欢跑步视频");var settings=store.settings(owner);
        var automatic=ledger.declare(owner,derived.sessionId,derived.id,new Content(Scope.user(),PersonalMemory.Kind.PREFERENCE,"长期喜欢跑步视频",null),"derived",false,settings.consentEpoch(),settings.eraseEpoch());
        db.update("UPDATE agent_run SET data_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=?",derived.id.toString());
        assertEquals("MEMORY_NOT_FOUND",assertThrows(AgentException.class,()->store.get(owner,automatic.id())).code());
    }
    @Test void snapshotsKeepOldScopeAndRejectDeletedOrPausedDependencies(){
        var saved=store.create(owner,new Content(Scope.user(),PersonalMemory.Kind.PREFERENCE,"个人偏好",null),"save");var request=run("测试快照");ledger.begin(request);var boundary=ledger.boundary(owner,request.id);
        store.update(owner,saved.id(),1,new Content(new Scope(ScopeType.VIDEO,"1001"),PersonalMemory.Kind.GOAL,"新的视频目标",null),"move");
        assertEquals(Scope.user(),ledger.read(owner,saved.id(),1,boundary.sequence()).content().scope());
        ledger.consume(request,List.of(ledger.read(owner,saved.id(),1,boundary.sequence())));
        store.settings(owner,store.settings(owner).version(),Mode.OFF);
        assertEquals("MEMORY_CONTEXT_INVALIDATED",assertThrows(AgentException.class,()->ledger.guard(request)).code());
    }
    @Test void terminalOutboxBuildsRealDurableJobAndStoresOnlyGroundedQuote() throws Exception {
        ledger.auto(owner,0,true);var origin=run("我长期偏好有实例的技术讲解");origin.succeed(new Answer("收到",List.of()));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);List<Map<String,Object>> jobs=List.of();
        while(System.nanoTime()<until){runs.deliverOutbox();background.tick();jobs=background.jobs(owner,origin.id);if(jobs.stream().anyMatch(j->"SUCCEEDED".equals(j.get("state"))))break;Thread.sleep(20);}
        assertTrue(jobs.stream().anyMatch(j->"SUCCEEDED".equals(j.get("state"))),jobs.toString());
        assertEquals(origin.message,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0).content().text());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE tenant_id=?",Integer.class,owner.tenantId()));
        assertNotNull(jobs.get(0).get("trace_id"));
    }
    @Test void pausedOrResetEpochCannotBeBypassedByALateExtractionWriter(){
        ledger.auto(owner,0,true);var origin=run("持续目标：学习数据库");var before=store.settings(owner);store.reset(owner,"reset");
        assertEquals("MEMORY_CONTEXT_INVALIDATED",assertThrows(AgentException.class,()->ledger.declare(owner,origin.sessionId,origin.id,new Content(Scope.user(),PersonalMemory.Kind.GOAL,"学习数据库",null),"late",false,before.consentEpoch(),before.eraseEpoch())).code());
        assertTrue(store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().isEmpty());
    }
    @Test void inheritedAnswersRetainMemoryRootsAcrossThreeTurnsAndFailFinalCommitAfterForget(){
        var saved=store.create(owner,new Content(Scope.user(),PersonalMemory.Kind.PREFERENCE,"先解释原理",null),"root");
        var first=run("第一轮");ledger.begin(first);ledger.consume(first,List.of(ledger.read(owner,saved.id(),1,ledger.boundary(owner,first.id).sequence())),true);first.succeed(new Answer("按偏好回答",List.of()));
        var second=run("第二轮");ledger.begin(second);ledger.inherit(second,List.of(message(first,"按偏好回答")));second.succeed(new Answer("延续第一轮",List.of()));
        var third=run("第三轮");ledger.begin(third);ledger.inherit(third,List.of(message(second,"延续第一轮")));
        assertEquals(saved.id(),ledger.boundary(owner,third.id).refs().get(0).id());
        store.delete(owner,saved.id(),1,"forget-root");
        assertFalse(ledger.historyAllowed(owner,second.id));
        assertEquals("MEMORY_CONTEXT_INVALIDATED",assertThrows(AgentException.class,()->third.succeed(new Answer("不应提交",List.of()))).code());
        assertTrue(ledger.inherit(third,List.of(message(second,"延续第一轮"))).get(0).text().contains("不可用"));
    }
    @Test void liveVideoRevocationBlocksOldScopeAtHardBoundary(){
        var saved=store.create(owner,new Content(new Scope(ScopeType.VIDEO,"1001"),PersonalMemory.Kind.GOAL,"改善视频节奏",null),"video");
        var consumer=run("看一下这个视频");ledger.begin(consumer);ledger.consume(consumer,List.of(ledger.read(owner,saved.id(),1,ledger.boundary(owner,consumer.id).sequence())));
        when(videoPages.page(eq(1001L),any())).thenThrow(new AgentException("VIDEO_NOT_FOUND","unavailable"));
        assertEquals("MEMORY_CONTEXT_INVALIDATED",assertThrows(AgentException.class,()->ledger.guard(consumer)).code());
    }
    @Test void explicitWriterCannotSurvivePauseResumeOrLostSessionOwnership(){
        var request=run("记住我偏好短回答");ledger.begin(request);
        store.settings(owner,store.settings(owner).version(),Mode.OFF);store.settings(owner,store.settings(owner).version(),Mode.ON);
        assertEquals("MEMORY_CONTEXT_INVALIDATED",assertThrows(AgentException.class,()->ledger.declare(request,new Content(Scope.user(),PersonalMemory.Kind.PREFERENCE,"短回答",null),"late-explicit",true)).code());
        var active=run("记住我偏好实例");ledger.begin(active);active.cancel();
        assertEquals("RUN_TERMINATED",assertThrows(AgentException.class,()->ledger.declare(active,new Content(Scope.user(),PersonalMemory.Kind.PREFERENCE,"实例",null),"cancelled-explicit",true)).code());
    }
    private MemoryTypes.Message message(RunState run,String text){return new MemoryTypes.Message(UUID.randomUUID(),run.id,1,"assistant",text,Instant.now());}
    @Test void derivedSourceProtectionExistsBeforeTheRootSpanCapturesInput(){
        assertTrue(traceDetails.bind(io.opentelemetry.context.Context.root(),UUID.randomUUID()).get(com.tianmu.agent.harness.trace.TraceDetails.MEMORY).get());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"reset","pause","delete"})
    void revocationBetweenSourceReadAndDispatchPreventsTheProviderCall(String action)throws Exception{
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var providerCalls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv->{reached.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));inv.<com.tianmu.agent.runtime.control.CallControl>getArgument(3).beforeDispatch();providerCalls.incrementAndGet();var empty=json.createObjectNode();empty.putArray("items");return empty;}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        ledger.auto(owner,0,true);var origin=run("我长期偏好结构清楚的技术讲解");origin.succeed(new Answer("收到",List.of()));runs.deliverOutbox();background.tick();
        assertTrue(reached.await(5,TimeUnit.SECONDS));
        try{switch(action){case "reset"->store.reset(owner,"before-send");case "pause"->store.settings(owner,store.settings(owner).version(),Mode.OFF);case "delete"->runs.deleteSession(origin.sessionId,owner);default->fail();}}finally{release.countDown();}
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){if(background.jobs(owner,origin.id).stream().anyMatch(j->"CANCELLED".equals(j.get("state"))))break;Thread.sleep(20);}
        assertEquals(0,providerCalls.get());assertTrue(background.jobs(owner,origin.id).stream().anyMatch(j->"CANCELLED".equals(j.get("state"))));
    }
}
