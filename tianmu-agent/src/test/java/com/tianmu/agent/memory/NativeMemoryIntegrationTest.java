package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
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
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="AGENT_MEMORY_IT",matches="true")
@ActiveProfiles("demo")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties={"agent.memory.enabled=true","agent.memory.consolidation.enabled=true","agent.storage.mode=mysql","agent.storage.object-store=file","agent.storage.object-directory=./target/test-artifacts/native-memory","agent.storage.redis.enabled=false","harness.trace.directory=./target/test-traces/native-memory"})
class NativeMemoryIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){MemoryRuntimeIntegrationTest.database(registry);}
    @Autowired MysqlRunRepository runs;@Autowired PersonalMemoryStore store;@Autowired MemoryLedger ledger;
    @Autowired MemoryRecallService recall;@Autowired MemoryConsolidation consolidation;@Autowired DataSource source;@Autowired ObjectMapper json;
    @Autowired com.tianmu.agent.runtime.ToolBatchLedger batches;
    @MockBean MemoryBackground automaticBackground;@MockBean com.tianmu.agent.runtime.RunService automaticRecovery;
    @MockBean MemoryModelPort model;
    AccessScope owner;JdbcTemplate db;List<RunState> created;
    @BeforeEach void setup(){owner=new AccessScope("native_"+UUID.randomUUID().toString().substring(0,12),"alice",Set.of());db=new JdbcTemplate(source);created=new ArrayList<>();ledger.auto(owner,0,true);}
    RunState run(String message,UUID session){var r=runs.create(message,owner,session,UUID.randomUUID().toString(),null).run();r.start();created.add(r);consolidation.observe(db,owner,r.sessionId,r.id);r.succeed(new Answer("收到",List.of()));return r;}
    Map<String,Object> job(RunState run){
        var settings=store.settings(owner);String id=UUID.randomUUID().toString(),lease=UUID.randomUUID().toString();
        db.update("INSERT INTO agent_memory_job(job_id,tenant_id,owner_user_id,session_id,run_id,consent_epoch,erase_epoch,model_version,job_type,plan_json,generation_key,state,lease_token,lease_until,available_at,created_at) VALUES(?,?,?,?,?,?,?,'test-v1','CONSOLIDATE',?,'test','RUNNING',?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 SECOND),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",id,owner.tenantId(),owner.userId(),run.sessionId.toString(),run.id.toString(),settings.consentEpoch(),settings.eraseEpoch(),"{\"policy\":\""+MemoryConsolidation.VERSION+"\"}",lease);
        db.update("INSERT INTO agent_memory_window(tenant_id,owner_user_id,session_id,run_id,message_seq,observation_seq) SELECT tenant_id,owner_user_id,session_id,run_id,message_seq,observation_seq FROM agent_memory_observation WHERE tenant_id=? AND owner_user_id=? AND run_id=?",owner.tenantId(),owner.userId(),run.id.toString());
        return new HashMap<>(db.queryForMap("SELECT * FROM agent_memory_job WHERE job_id=?",id));
    }
    JsonNode response(Map<String,Object> payload,String value,boolean both)throws Exception{
        var root=json.createObjectNode().put("complete",true).put("overflow",false);String current=payload.get("new_message_id").toString();root.putArray("processed_message_ids").add(current);
        var c=root.putArray("candidates").addObject().put("kind","PREFERENCE").put("attribute","explanation_style").put("qualifier","").put("value",value).put("text",value.equals("examples_first")?"偏好先举例再解释。":"偏好先讲原理。").put("topic","");
        var refs=c.putArray("support");for(var s:(List<MemoryConsolidation.Source>)payload.get("messages"))if(s.role().equals("USER") && (both || s.messageId().equals(current)))refs.addObject().put("message_id",s.messageId()).put("quote",s.text());return root;
    }
    void extract(Map<String,Object> job,String value,boolean both){
        doAnswer(inv->{inv.<com.tianmu.agent.runtime.control.CallControl>getArgument(3).beforeDispatch();return response(inv.getArgument(1),value,both);}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());consolidation.work(job,model);
    }
    @AfterEach void cleanup(){
        for(var r:created)try{runs.deleteSession(r.sessionId,owner);}catch(AgentException ignored){}
        for(String table:List.of("candidate","fact","window","cursor","observation","job","run","keyword","dependency","revision","source","item","mutation","tombstone","topic","account"))db.update("DELETE FROM agent_memory_"+table+" WHERE tenant_id=? AND owner_user_id=?",owner.tenantId(),owner.userId());
    }
    @Test void repeatedRecallAndProcessRestoreKeepExactlyTheOriginalPacketEvenWhenEmpty(){
        var request=runs.create("聊聊",owner,null,"once",null).run();request.start();created.add(request);recall.begin(request);recall.recall(request);
        store.create(owner,new Content(Scope.user(),PersonalMemory.Kind.PREFERENCE,"偏好先举例",null),"later");recall.recall(request);
        assertTrue(request.memory().persistent().isEmpty());assertTrue(ledger.boundary(owner,request.id).recalled());
        request.memoryPacketReady(false);recall.restore(request);assertTrue(request.memory().persistent().isEmpty());
    }
    @Test void batchResultsUseProtectedRunStorageAndDisappearWithDeletedSession(){
        var request=runs.create("批次台账",owner,null,"batch-ledger",null).run();request.start();created.add(request);
        var calls=List.of(new ToolCall("query_video_metrics",Map.of("video_id",1),"a","g"),new ToolCall("query_video_metrics",Map.of("video_id",2),"b","g"));
        batches.prepare(request,calls,true);var result=new ToolResult("query_video_metrics",Map.of("value",42),null);batches.complete(request,0,result,true);
        assertEquals(result,batches.replay(request,0));assertNull(batches.replay(request,1));
        assertThrows(AgentException.class,()->batches.prepare(request,List.of(calls.get(1),calls.get(0)),true));
        request.cancel();assertThrows(AgentException.class,()->batches.replay(request,0));runs.deleteSession(request.sessionId,owner);runs.garbageCollect();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_tool_call WHERE run_id=?",Integer.class,request.id.toString()));
    }
    @Test void multiTurnFactRequiresEveryUserSourceAndCommitsOnlyContiguousWatermark(){
        var first=run("我喜欢先给例子。",null);var second=run("以后也按这个顺序。",first.sessionId);var j1=job(first);var j2=job(second);
        extract(j2,"examples_first",true);
        var entry=store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0);
        assertEquals(2,ledger.sourceRefs(owner,entry).size());
        assertEquals(0,db.queryForObject("SELECT committed_through FROM agent_memory_cursor WHERE tenant_id=?",Long.class,owner.tenantId()));
        extract(j1,"examples_first",false);
        assertEquals(3,db.queryForObject("SELECT committed_through FROM agent_memory_cursor WHERE tenant_id=?",Long.class,owner.tenantId()));
        assertEquals(1,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().size());
        db.update("UPDATE agent_message SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=? AND role='USER'",first.id.toString());
        assertThrows(AgentException.class,()->store.get(owner,entry.id()));assertThrows(AgentException.class,()->ledger.read(owner,entry.id(),entry.version(),store.settings(owner).sequence()));
    }
    @Test void lateOlderObservationCannotOverwriteNewerAndManualCorrectionIsProtected(){
        var old=run("我喜欢先举例。",null);var newer=run("以后请先讲原理。",old.sessionId);var oldJob=job(old);var newJob=job(newer);
        extract(newJob,"concept_first",false);extract(oldJob,"examples_first",false);
        var entry=store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0);assertEquals("偏好先讲原理。",entry.content().text());
        assertEquals("STALE",db.queryForObject("SELECT decision FROM agent_memory_candidate WHERE job_id=?",String.class,oldJob.get("job_id")));
        store.update(owner,entry.id(),entry.version(),new Content(Scope.user(),PersonalMemory.Kind.PREFERENCE,"手工修订：按问题决定",null),"manual");
        var third=run("还是先举例。",old.sessionId);var j3=job(third);extract(j3,"examples_first",false);
        assertEquals("CONFLICT",db.queryForObject("SELECT decision FROM agent_memory_candidate WHERE job_id=?",String.class,j3.get("job_id")));
        assertEquals("手工修订：按问题决定",store.get(owner,entry.id()).content().text());
    }
    @Test void revokingEarlierManifestSourceBeforeDispatchPreventsProviderCall(){
        var old=run("我希望讲解带例子。",null);var current=run("以后也这样。",old.sessionId);var job=job(current);var sent=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv->{
            db.update("UPDATE agent_message SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=? AND role='USER'",old.id.toString());
            inv.<com.tianmu.agent.runtime.control.CallControl>getArgument(3).beforeDispatch();sent.incrementAndGet();return response(inv.getArgument(1),"examples_first",true);
        }).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        assertThrows(AgentException.class,()->consolidation.work(job,model));assertEquals(0,sent.get());
        assertTrue(store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().isEmpty());
        assertEquals(0,db.queryForObject("SELECT committed_through FROM agent_memory_cursor WHERE tenant_id=?",Long.class,owner.tenantId()));
    }
    @Test void emptyCompleteWindowCommitsButOverflowRollsBackWholeWindow(){
        var first=run("你好",null);var j1=job(first);
        doAnswer(inv->{var result=response(inv.getArgument(1),"examples_first",false);((com.fasterxml.jackson.databind.node.ArrayNode)result.path("candidates")).removeAll();return result;}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        consolidation.work(j1,model);assertEquals(1,db.queryForObject("SELECT committed_through FROM agent_memory_cursor WHERE tenant_id=?",Long.class,owner.tenantId()));
        var next=run("以后需要很多很多偏好",first.sessionId);var j2=job(next);
        doAnswer(inv->{var result=response(inv.getArgument(1),"examples_first",false);((com.fasterxml.jackson.databind.node.ObjectNode)result).put("overflow",true);return result;}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        assertThrows(AgentException.class,()->consolidation.work(j2,model));assertTrue(store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().isEmpty());
        assertEquals("PENDING",db.queryForObject("SELECT state FROM agent_memory_window WHERE run_id=?",String.class,next.id.toString()));
        assertEquals(1,db.queryForObject("SELECT committed_through FROM agent_memory_cursor WHERE tenant_id=?",Long.class,owner.tenantId()));
    }
    @Test void staleWorkerCannotSkipTheNewLeaseOrAdvanceItsWatermark(){
        var request=run("长期希望先举例。",null);var old=job(request);String replacement=UUID.randomUUID().toString();
        db.update("UPDATE agent_memory_job SET lease_token=? WHERE job_id=?",replacement,old.get("job_id"));
        consolidation.cancel(old,"MEMORY_CONTEXT_INVALIDATED");
        assertEquals("RUNNING",db.queryForObject("SELECT state FROM agent_memory_job WHERE job_id=?",String.class,old.get("job_id")));
        assertEquals("PENDING",db.queryForObject("SELECT state FROM agent_memory_window WHERE run_id=?",String.class,request.id.toString()));
        assertEquals(0,db.queryForObject("SELECT committed_through FROM agent_memory_cursor WHERE tenant_id=?",Long.class,owner.tenantId()));
        var current=new HashMap<>(old);current.put("lease_token",replacement);consolidation.cancel(current,"MEMORY_CONTEXT_INVALIDATED");
        assertEquals("SKIPPED",db.queryForObject("SELECT state FROM agent_memory_window WHERE run_id=?",String.class,request.id.toString()));
        assertEquals(1,db.queryForObject("SELECT committed_through FROM agent_memory_cursor WHERE tenant_id=?",Long.class,owner.tenantId()));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"examples_first","concept_first"})
    void naturallyExpiredAutomaticFactCanBeLearnedAgainButExplicitForgetCannot(String value){
        var first=run("长期请先举例。",null);extract(job(first),"examples_first",false);
        var old=store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0);
        db.update("UPDATE agent_memory_source SET source_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE origin_run_id=?",first.id.toString());
        assertThrows(AgentException.class,()->store.get(owner,old.id()));
        var next=run(value.equals("examples_first")?"以后讲解都先举例。":"以后先讲原理。",first.sessionId);var j2=job(next);extract(j2,value,false);
        assertEquals("REANCHOR_AUTO",db.queryForObject("SELECT decision FROM agent_memory_candidate WHERE job_id=?",String.class,j2.get("job_id")));
        var fresh=store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0);assertNotEquals(old.id(),fresh.id());
        store.delete(owner,fresh.id(),fresh.version(),"forget");
        var again=run("先举例的表达方式。",first.sessionId);var j3=job(again);extract(j3,"examples_first",false);
        assertEquals("REJECT_POLICY",db.queryForObject("SELECT decision FROM agent_memory_candidate WHERE job_id=?",String.class,j3.get("job_id")));
        assertTrue(store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().isEmpty());
    }
    @Test void newestSameValueConfirmationFencesAnIntermediateLateCorrection(){
        var first=run("长期先举例。",null);extract(job(first),"examples_first",false);
        var middle=run("先讲原理。",first.sessionId);var middleJob=job(middle);
        var newest=run("最后还是先举例。",first.sessionId);var newestJob=job(newest);extract(newestJob,"examples_first",false);
        assertEquals("NOOP_DUPLICATE",db.queryForObject("SELECT decision FROM agent_memory_candidate WHERE job_id=?",String.class,newestJob.get("job_id")));
        extract(middleJob,"concept_first",false);
        assertEquals("STALE",db.queryForObject("SELECT decision FROM agent_memory_candidate WHERE job_id=?",String.class,middleJob.get("job_id")));
        assertEquals("偏好先举例再解释。",store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0).content().text());
    }
}
