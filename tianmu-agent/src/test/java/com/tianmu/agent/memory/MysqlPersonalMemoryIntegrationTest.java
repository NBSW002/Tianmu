package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL only; deliberately refuses any database name not ending in _it. */
@EnabledIfEnvironmentVariable(named="AGENT_MEMORY_IT",matches="true")
class MysqlPersonalMemoryIntegrationTest {
    static HikariDataSource source;
    static JdbcTemplate db;
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    AccessScope owner;
    MysqlPersonalMemoryStore store;
    @BeforeAll static void database() {
        String url=System.getenv("AGENT_MEMORY_JDBC_URL");
        assertNotNull(url);assertTrue(url.matches("jdbc:mysql://[^/]+/[^?]*_it(?:\\?.*)?"),"Dedicated _it database required");
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(System.getenv("AGENT_MEMORY_DB_USER"));
        config.setPassword(System.getenv().getOrDefault("AGENT_MEMORY_DB_PASSWORD",""));config.setMaximumPoolSize(6);
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        source=new HikariDataSource(config);db=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
    }
    @AfterAll static void close(){if(source!=null)source.close();}
    @BeforeEach void owner(){owner=new AccessScope(UUID.randomUUID().toString(),"alice",Set.of(1001L));store=new MysqlPersonalMemoryStore(source,JSON);}
    @AfterEach void cleanup(){
        for(String table:List.of("keyword","dependency","revision","source","item","mutation","tombstone","account"))
            db.update("DELETE FROM agent_memory_"+table+" WHERE tenant_id=? AND owner_user_id=?",owner.tenantId(),owner.userId());
    }
    private Content user(String text){return new Content(Scope.user(),Kind.PREFERENCE,text,null);}
    private String code(Runnable action){return assertThrows(AgentException.class,action::run).code();}
    @Test void manualMemorySurvivesStoreReloadAndCannotCrossOwnerOrTenant() {
        var receipt=store.create(owner,user("以后简短回答"),"save");
        var reload=new MysqlPersonalMemoryStore(source,JSON);
        assertEquals("以后简短回答",reload.get(owner,receipt.id()).content().text());
        assertEquals(1,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"简短").items().size());
        assertEquals(0,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"unrelated").items().size());
        var bob=new AccessScope(owner.tenantId(),"bob",Set.of(1001L));
        assertEquals("MEMORY_NOT_FOUND",code(()->store.get(bob,receipt.id())));
        assertEquals("MEMORY_NOT_FOUND",code(()->store.scope(new AccessScope("other",owner.userId(),Set.of()),receipt.id())));
    }
    @Test void idempotencyDoesNotDuplicateOrResurrectErasedMemory() {
        var saved=store.create(owner,user("private declaration"),"same");
        assertEquals(saved,store.create(owner,user("private declaration"),"same"));
        assertEquals("IDEMPOTENCY_CONFLICT",code(()->store.create(owner,user("different"),"same")));
        var deleted=store.delete(owner,saved.id(),1,"delete");
        assertEquals(deleted,store.delete(owner,saved.id(),1,"delete"));
        assertEquals(saved,store.create(owner,user("private declaration"),"same"));
        assertEquals("MEMORY_NOT_FOUND",code(()->store.get(owner,saved.id())));
        assertEquals(0,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_revision WHERE tenant_id=? AND content<>''",Integer.class,owner.tenantId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_source WHERE tenant_id=? AND declaration<>''",Integer.class,owner.tenantId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_keyword WHERE tenant_id=?",Integer.class,owner.tenantId()));
    }
    @Test void scopeAndKindAreVersionedAndBothVideoGrantsAreRequired() {
        var saved=store.create(owner,user("alphaold"),"save");
        var next=new Content(new Scope(ScopeType.VIDEO,"1001"),Kind.GOAL,"betanew",null);
        assertEquals(2,store.update(owner,saved.id(),1,next,"update").version());
        assertEquals("USER",db.queryForObject("SELECT scope_type FROM agent_memory_revision WHERE tenant_id=? AND version=1",String.class,owner.tenantId()));
        assertEquals("VIDEO",db.queryForObject("SELECT scope_type FROM agent_memory_revision WHERE tenant_id=? AND version=2",String.class,owner.tenantId()));
        assertEquals(2,db.queryForObject("SELECT valid_to_seq FROM agent_memory_revision WHERE tenant_id=? AND version=1",Long.class,owner.tenantId()));
        var revoked=new AccessScope(owner.tenantId(),owner.userId(),Set.of());
        assertEquals("MEMORY_NOT_FOUND",code(()->store.get(revoked,saved.id())));
        assertEquals("MEMORY_NOT_FOUND",code(()->store.update(revoked,saved.id(),2,user("move back"),"bad-update")));
        assertEquals("MEMORY_VERSION_CONFLICT",code(()->store.update(owner,saved.id(),1,next,"stale")));
    }
    @Test void optimisticConcurrencyPreventsLostUpdate() throws Exception {
        var saved=store.create(owner,user("original"),"save");
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<String>> results=new ArrayList<>();
            for(int i=0;i<2;i++){final int index=i;results.add(pool.submit(()->{start.await();try{new MysqlPersonalMemoryStore(source,JSON).update(owner,saved.id(),1,user("value "+index),"update-"+index);return "OK";}catch(AgentException e){return e.code();}}));}
            start.countDown();var outcomes=new ArrayList<String>();for(var result:results)outcomes.add(result.get(10,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(outcomes,"OK"));assertEquals(1,Collections.frequency(outcomes,"MEMORY_VERSION_CONFLICT"));
            assertEquals(2,store.get(owner,saved.id()).version());
        } finally {pool.shutdownNow();}
    }
    @Test void simultaneousIdempotentRequestsCreateOnlyOneItem() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Receipt>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return new MysqlPersonalMemoryStore(source,JSON).create(owner,user("same"),"same-key");}));
            start.countDown();Set<Receipt> results=new HashSet<>();for(var future:futures)results.add(future.get(10,TimeUnit.SECONDS));
            assertEquals(1,results.size());assertEquals(1,store.settings(owner).sequence());
        } finally {pool.shutdownNow();}
    }
    @Test void pausedAccountAllowsErasureButNotSavingAndSettingsUseCas() {
        var saved=store.create(owner,user("erase me"),"save");var setting=store.settings(owner,0,Mode.OFF);
        assertEquals(1,setting.consentEpoch());
        assertEquals("MEMORY_DISABLED",code(()->store.create(owner,user("no"),"disabled")));
        assertEquals("MEMORY_VERSION_CONFLICT",code(()->store.settings(owner,0,Mode.ON)));
        store.delete(owner,saved.id(),1,"delete");assertEquals(1,store.settings(owner).eraseEpoch());
        assertEquals("RESET",store.reset(owner,"reset").action());
    }
    @Test void expiredItemsDoNotConsumeQuotaAndRemainErasable() {
        Instant base=Instant.now();var initial=new MysqlPersonalMemoryStore(source,JSON,Clock.fixed(base,ZoneOffset.UTC),1);
        var expired=initial.create(owner,new Content(Scope.user(),Kind.GOAL,"expire",base.plusSeconds(10)),"save");
        var later=new MysqlPersonalMemoryStore(source,JSON,Clock.fixed(base.plusSeconds(20),ZoneOffset.UTC),1);
        assertEquals("MEMORY_NOT_FOUND",code(()->later.get(owner,expired.id())));
        assertDoesNotThrow(()->later.create(owner,user("new item"),"new"));
        assertDoesNotThrow(()->later.delete(owner,expired.id(),1,"delete-expired"));
        assertEquals(1,later.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().size());
    }
    @Test void failedKeywordWriteRollsBackSourcesItemsSequenceAndReceipt() {
        String trigger="memory_test_failure";
        db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON agent_memory_keyword FOR EACH ROW BEGIN IF NEW.tenant_id='"+owner.tenantId()+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'; END IF; END");
        try {
            assertEquals("MEMORY_UNAVAILABLE",code(()->store.create(owner,user("trigger"),"fail")));
            assertEquals(0,store.settings(owner).sequence());
            for(String table:List.of("item","source","mutation"))assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_"+table+" WHERE tenant_id=?",Integer.class,owner.tenantId()));
        } finally {db.execute("DROP TRIGGER "+trigger);}
    }
    @Test void setBasedResetErasesFullQuotaAndEveryHistoricalVersion() {
        // Populate through the real API so every row has its source and dependency invariants.
        var seed=store.create(owner,user("seed"),"seed");
        store.update(owner,seed.id(),1,user("updated seed"),"seed-update");
        for(int i=1;i<1000;i++)store.create(owner,user("remember "+i),"fixture-"+i);
        long started=System.nanoTime();var receipt=store.reset(owner,"reset");
        assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(5))<0);
        assertEquals(receipt,store.reset(owner,"reset"));
        assertEquals(0,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_revision WHERE tenant_id=? AND content<>''",Integer.class,owner.tenantId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_source WHERE tenant_id=? AND declaration<>''",Integer.class,owner.tenantId()));
        assertEquals(1000,db.queryForObject("SELECT COUNT(*) FROM agent_memory_tombstone WHERE tenant_id=?",Integer.class,owner.tenantId()));
    }
    @Test void failedResetRollsBackEveryErasureAndItsEpoch() {
        var saved=store.create(owner,user("private original"),"save");var before=store.settings(owner);
        String trigger="memory_reset_failure";
        db.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON agent_memory_item FOR EACH ROW BEGIN IF NEW.tenant_id='"+owner.tenantId()+"' AND NEW.status='DELETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reset failure'; END IF; END");
        try {
            assertEquals("MEMORY_UNAVAILABLE",code(()->store.reset(owner,"reset-failure")));
            assertEquals(before,store.settings(owner));
            assertEquals("private original",store.get(owner,saved.id()).content().text());
            assertEquals(1,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"original").items().size());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_tombstone WHERE tenant_id=?",Integer.class,owner.tenantId()));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_mutation WHERE tenant_id=? AND idempotency_key='reset-failure'",Integer.class,owner.tenantId()));
        } finally {db.execute("DROP TRIGGER "+trigger);}
    }
}
