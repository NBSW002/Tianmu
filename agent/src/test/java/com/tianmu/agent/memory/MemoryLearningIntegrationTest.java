package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.tianmu.agent.memory.PersonalMemory.Kind;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.adapters.storage.MysqlRunRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="AGENT_MEMORY_IT",matches="true")
@ActiveProfiles("demo")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties={"agent.memory.enabled=true","agent.memory.consolidation.enabled=true","agent.memory.policy.dream-min-changes=1","agent.storage.mode=mysql","agent.storage.object-store=file","agent.storage.object-directory=./target/test-artifacts/memory-learning","agent.storage.redis.enabled=false","harness.trace.directory=./target/test-traces/memory-learning"})
class MemoryLearningIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){MemoryRuntimeIntegrationTest.database(registry);}
    @Autowired MysqlRunRepository runs;@Autowired PersonalMemoryStore store;@Autowired MemoryLedger ledger;
    @Autowired MemoryConsolidation learning;@Autowired MemoryDream dream;@Autowired MemoryRecallService recall;@Autowired DataSource source;@Autowired ObjectMapper json;
    @Autowired com.tianmu.agent.preparation.PreparationService preparation;
    @Autowired org.springframework.context.ApplicationContext beans;
    @MockBean MemoryBackground automaticBackground;@MockBean com.tianmu.agent.runtime.RunService automaticRecovery;
    @MockBean MemoryModelPort model;
    AccessScope owner;JdbcTemplate db;List<RunState> created;
    @BeforeEach void setup(){owner=new AccessScope("learn_"+UUID.randomUUID().toString().substring(0,12),"alice",Set.of());db=new JdbcTemplate(source);created=new ArrayList<>();when(model.version()).thenReturn("learning-test-v1");}
    RunState run(String text,UUID session){var r=runs.create(text,owner,session,UUID.randomUUID().toString(),null).run();r.start();created.add(r);learning.observe(db,owner,r.sessionId,r.id);return r;}
    void done(RunState run){run.succeed(new Answer("收到",List.of()));learning.enqueue(db,owner,run.sessionId,run.id,model.version());}
    Map<String,Object> job(RunState r,boolean explicit){
        var s=store.settings(owner);String id=UUID.randomUUID().toString(),lease=UUID.randomUUID().toString();
        db.update("INSERT INTO agent_memory_job(job_id,tenant_id,owner_user_id,session_id,run_id,consent_epoch,erase_epoch,model_version,job_type,plan_json,generation_key,state,lease_token,lease_until,available_at,created_at) VALUES(?,?,?,?,?,?,?,?,'CONSOLIDATE',?,?,'RUNNING',?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",id,owner.tenantId(),owner.userId(),r.sessionId.toString(),r.id.toString(),s.consentEpoch(),s.eraseEpoch(),model.version(),"{\"policy\":\""+MemoryConsolidation.VERSION+"\",\"explicit\":"+explicit+"}",id,lease);
        return new HashMap<>(db.queryForMap("SELECT * FROM agent_memory_job WHERE job_id=?",id));
    }
    JsonNode response(Map<String,Object> payload,boolean fact){
        var root=json.createObjectNode().put("complete",true).put("overflow",false);root.set("processed_message_ids",json.valueToTree(payload.get("new_message_ids")));var candidates=root.putArray("candidates");
        if(fact){var c=candidates.addObject().put("kind","PROFILE").put("attribute","school").put("qualifier","").put("value","天津大学").put("text","用户在天津大学就读。").put("topic","");var refs=c.putArray("support");
            for(var s:(List<MemoryConsolidation.Source>)payload.get("messages"))if(s.role().equals("USER"))refs.addObject().put("message_id",s.messageId()).put("quote",s.text());}
        return root;
    }
    void extract(Map<String,Object> job,boolean fact){doAnswer(inv->{inv.<com.tianmu.agent.runtime.control.CallControl>getArgument(3).beforeDispatch();return response(inv.getArgument(1),fact);}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());learning.work(job,model);}
    @AfterEach void cleanup(){
        for(var r:created)try{runs.deleteSession(r.sessionId,owner);}catch(AgentException ignored){}
        for(String table:List.of("index","dream","candidate","fact","window","cursor","observation","job","run","keyword","dependency","revision","source","item","mutation","tombstone","topic","account"))db.update("DELETE FROM agent_memory_"+table+" WHERE tenant_id=? AND owner_user_id=?",owner.tenantId(),owner.userId());
    }
    @Test void acceptedMessagesAreCoalescedAndEveryWindowCommitsTogether(){
        var first=run("我是天津大学的",null);done(first);var next=run("以后回答简短一些",first.sessionId);done(next);
        var j=job(first,false);extract(j,true);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_memory_window WHERE tenant_id=? AND state='DONE'",Integer.class,owner.tenantId()));
        assertEquals(3,db.queryForObject("SELECT committed_through FROM agent_memory_cursor WHERE tenant_id=?",Long.class,owner.tenantId()));
        assertEquals(Kind.PROFILE,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0).content().kind());
        var redundant=job(next,false);extract(redundant,true);verify(model,times(1)).evaluate(anyString(),anyMap(),anyInt(),any());
    }
    @Test void overflowKeepsProgressAndCanBeSplitBeforeRetry(){
        var first=run("我是天津大学的",null);done(first);var next=run("我喜欢简短回答",first.sessionId);done(next);var j=job(first,false);
        doAnswer(inv->{var out=(com.fasterxml.jackson.databind.node.ObjectNode)response(inv.getArgument(1),false);out.put("overflow",true);return out;}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        assertEquals("CANDIDATE_CAPACITY",assertThrows(AgentException.class,()->learning.work(j,model)).code());
        assertEquals(0,db.queryForObject("SELECT committed_through FROM agent_memory_cursor WHERE tenant_id=?",Long.class,owner.tenantId()));
        assertTrue(learning.reduceBatch(j));assertEquals("PENDING",db.queryForObject("SELECT state FROM agent_memory_job WHERE job_id=?",String.class,j.get("job_id")));
    }
    @Test void explicitSaveWorksWithAutomaticLearningOffAndDoesNotConsumeOtherWindows(){
        var first=run("我是天津大学的",null);done(first);var unrelated=run("我喜欢草莓",first.sessionId);done(unrelated);
        var save=run("存",first.sessionId);var receipt=learning.requestSave(save);assertEquals("QUEUED",receipt.get("status"));assertEquals(false,receipt.get("saved"));
        var explicit=job(save,true);extract(explicit,true);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_memory_window WHERE tenant_id=? AND state='DONE'",Integer.class,owner.tenantId()));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_memory_window WHERE tenant_id=? AND state='PENDING'",Integer.class,owner.tenantId()));
        var settings=store.settings(owner);ledger.auto(owner,settings.version(),false);
        done(save);var another=run("记一下，我在天津大学",first.sessionId);learning.requestSave(another);extract(job(another,true),true);
        assertFalse(store.settings(owner).autoExtract());
    }
    @Test void saveEntrySkipsSkillAndModelAndAcknowledgesOnlyQueue(){
        var request=run("请记住我在天津大学",null);
        beans.getBean(com.tianmu.agent.application.ContextLifecycle.class).begin(request);
        var result=preparation.prepare(request,request.message);
        assertEquals(com.tianmu.agent.core.PreparationTypes.Route.DIRECT,result.route());assertTrue(result.reply().text().contains("后台"));assertFalse(result.reply().text().contains("已记住"));
        request.prepared(result);beans.getBean(com.tianmu.agent.application.DeliveryService.class).acknowledgement(request,result.reply());
        assertEquals(Status.SUCCEEDED,request.snapshot().status());assertEquals(0,request.snapshot().modelCalls());
        verify(model,never()).evaluate(anyString(),anyMap(),anyInt(),any());
    }
    @Test void deleteBetweenExtractionAndPublicationCannotResurrectMemory(){
        var first=run("我是天津大学的",null);done(first);extract(job(first,false),true);
        var entry=store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0);
        var next=run("还是天津大学",first.sessionId);var j=job(next,false);
        doAnswer(inv->{var result=response(inv.getArgument(1),true);store.delete(owner,entry.id(),entry.version(),"erase-during-call");return result;}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        assertThrows(AgentException.class,()->learning.work(j,model));assertTrue(store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().isEmpty());
    }
    @Test void recallSeparatesCoreAndRelevantFactsAndRechecksDeletion(){
        var core=store.create(owner,new Content(Scope.user(),Kind.PREFERENCE,"用户偏好简洁回答",null),"core");
        db.update("INSERT INTO agent_memory_index(tenant_id,owner_user_id,memory_id,version,is_core,importance) VALUES(?,?,?,?,1,50)",owner.tenantId(),owner.userId(),core.id().toString(),core.version());
        var school=store.create(owner,new Content(Scope.user(),Kind.PROFILE,"用户在天津大学就读",null),"school");
        store.create(owner,new Content(Scope.user(),Kind.PREFERENCE,"用户喜欢草莓",null),"fruit");
        var r=run("我学校附近有什么",null);recall.begin(r);recall.recall(r);
        assertEquals(2,r.memory().persistent().size());assertTrue(r.memory().persistent().stream().anyMatch(e->e.id().equals(school.id())));
        store.delete(owner,school.id(),school.version(),"erase-school");assertThrows(AgentException.class,()->recall.verify(r));
    }
    Map<String,Object> dreamJob(){
        dream.schedule(model.version());var row=db.queryForMap("SELECT * FROM agent_memory_job WHERE tenant_id=? AND job_type='DREAM' ORDER BY created_at DESC LIMIT 1",owner.tenantId());
        String lease=UUID.randomUUID().toString();db.update("UPDATE agent_memory_job SET state='RUNNING',lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND) WHERE job_id=?",lease,row.get("job_id"));
        return new HashMap<>(db.queryForMap("SELECT * FROM agent_memory_job WHERE job_id=?",row.get("job_id")));
    }
    Receipt auto(RunState r,String text,String key){return ledger.declare(owner,r.sessionId,r.id,new Content(Scope.user(),Kind.PREFERENCE,text,null),key,false);}
    JsonNode merge(Receipt old,Receipt target){var out=json.createObjectNode().put("complete",true);out.putArray("changes").addObject().put("id",old.id().toString()).put("version",old.version()).put("action","MERGE").put("target",target.id().toString()).put("reason","两条均明确表示偏好简短回答");return out;}
    @Test void dreamRetiresDuplicatesWithoutMutatingTargetDependenciesAndTraceRechecksInputs(){
        var first=run("我偏好简短回答",null);done(first);var old=auto(first,"偏好简短回答","auto-old");
        var second=run("回答尽量简短",null);done(second);var target=auto(second,"偏好简短回答","auto-target");
        var original=ledger.sourceRefs(owner,store.get(owner,target.id()));var j=dreamJob();
        doAnswer(inv->{inv.<com.tianmu.agent.runtime.control.CallControl>getArgument(3).beforeDispatch();return merge(old,target);}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        dream.work(j,model);
        assertThrows(AgentException.class,()->store.get(owner,old.id()));
        assertEquals(original,ledger.sourceRefs(owner,store.get(owner,target.id())));
        // Retirement excludes recall but does not erase the source evidence needed to audit this dream.
        ledger.verifyJob(second,owner,UUID.fromString(j.get("job_id").toString()));
        store.delete(owner,old.id(),old.version(),"erase-retired");
        assertThrows(AgentException.class,()->ledger.verifyJob(second,owner,UUID.fromString(j.get("job_id").toString())));
    }
    @Test void concurrentMemoryWriteDiscardsDreamProposalWithoutPartialRetirement(){
        var first=run("我偏好简短回答",null);done(first);var old=auto(first,"偏好简短回答","old");
        var second=run("回答尽量简短",null);done(second);var target=auto(second,"偏好简短回答","target");var j=dreamJob();
        doAnswer(inv->{store.create(owner,new Content(Scope.user(),Kind.PROFILE,"用户在天津大学",null),"concurrent");return merge(old,target);}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        assertEquals("MEMORY_DREAM_STALE",assertThrows(AgentException.class,()->dream.work(j,model)).code());
        assertNotNull(store.get(owner,old.id()));assertNotNull(store.get(owner,target.id()));
    }
    @Test void dreamTraceReferencesAreInvalidatedByDeletingInputFromAnotherSession(){
        var first=run("我偏好简短回答",null);done(first);var old=auto(first,"偏好简短回答","old");
        var second=run("回答尽量简短",null);done(second);auto(second,"偏好简短回答","target");var j=dreamJob();
        doAnswer(inv->{var out=json.createObjectNode().put("complete",true);out.putArray("changes");return out;}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());dream.work(j,model);
        ledger.verifyJob(second,owner,UUID.fromString(j.get("job_id").toString()));
        runs.deleteSession(first.sessionId,owner);
        assertThrows(AgentException.class,()->ledger.verifyJob(second,owner,UUID.fromString(j.get("job_id").toString())));
    }
    @Test void newAccountsLearnByDefaultButExplicitOptOutPersists(){
        assertTrue(store.settings(owner).autoExtract());var r=run("你好",null);done(r);
        var settings=store.settings(owner);ledger.auto(owner,settings.version(),false);
        assertFalse(store.settings(owner).autoExtract());var next=run("我喜欢草莓",r.sessionId);done(next);
        assertFalse(store.settings(owner).autoExtract());
        assertEquals(0,db.queryForObject("SELECT eligible FROM agent_memory_observation WHERE run_id=?",Integer.class,next.id.toString()));
    }

    @Test void newerExplicitRequestUpdatesAnEarlierExplicitFactAndPromotesAutomaticSources(){
        var first=run("请记住，我来自天津大学",null);learning.requestSave(first);extract(job(first,true),true);done(first);
        var old=store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0);
        var next=run("请记住，我现在转到南开大学了",first.sessionId);learning.requestSave(next);
        doAnswer(inv->{var out=response(inv.getArgument(1),true);var item=(com.fasterxml.jackson.databind.node.ObjectNode)out.path("candidates").get(0);item.put("value","南开大学").put("text","用户表示自己现在转到南开大学。");return out;}).when(model).evaluate(anyString(),anyMap(),anyInt(),any());learning.work(job(next,true),model);
        var current=store.get(owner,old.id());assertEquals(old.version()+1,current.version());assertTrue(current.content().text().contains("南开大学"));
        assertEquals(1,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_source s JOIN agent_memory_dependency d ON d.source_id=s.source_id WHERE d.memory_id=? AND d.version=? AND s.source_kind<>'EXPLICIT_DECLARATION'",Integer.class,current.id().toString(),current.version()));
    }

    MemoryBackground worker(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool){
        var worker=new MemoryBackground(source,ledger,beans.getBeanProvider(MemoryModelPort.class),pool,json,beans.getBean(io.opentelemetry.api.trace.Tracer.class),beans.getBean(com.tianmu.agent.harness.trace.TraceDetails.class));
        ReflectionTestUtils.setField(worker,"memoryEnabled",true);ReflectionTestUtils.setField(worker,"consolidation",learning);ReflectionTestUtils.setField(worker,"dream",dream);return worker;
    }
    @Test void stoppedTasksHaveAnIdempotentOwnerScopedRetryWithoutRevivingDeletedSources(){
        var r=run("我是天津大学的",null);var failed=job(r,false);db.update("UPDATE agent_memory_job SET state='DEAD',lease_token=NULL,lease_until=NULL WHERE job_id=?",failed.get("job_id"));
        var pool=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();pool.initialize();
        try{
            var worker=worker(pool);var id=UUID.fromString(failed.get("job_id").toString());var first=worker.retry(owner,r.id,id,"retry-once");var replay=worker.retry(owner,r.id,id,"retry-once");
            assertEquals(first.get("job_id"),replay.get("job_id"));assertEquals("PENDING",first.get("state"));
            var foreign=new AccessScope(owner.tenantId(),"bob",Set.of());assertThrows(AgentException.class,()->worker.retry(foreign,r.id,id,"retry-other"));
            store.reset(owner,"erase-before-retry");assertThrows(AgentException.class,()->worker.retry(owner,r.id,id,"retry-after-erase"));
        }finally{pool.shutdown();}
    }
    @Test void twoWorkersCannotClaimTheSameAccountWhileAnotherTaskIsRunning(){
        var first=run("我是天津大学的",null);done(first);var next=run("回答简洁一些",first.sessionId);done(next);
        var pool=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();pool.initialize();
        try{
            var a=worker(pool);var b=worker(pool);
            for(var event:db.queryForList("SELECT * FROM agent_outbox WHERE tenant_id=? AND destination='MEMORY_CONSOLIDATE'",owner.tenantId()))a.accept(event);
            db.update("UPDATE agent_memory_window SET available_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE tenant_id=?",owner.tenantId());
            db.update("UPDATE agent_memory_job SET available_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE tenant_id=?",owner.tenantId());
            String orphan=UUID.randomUUID().toString();
            db.update("INSERT INTO agent_memory_job(job_id,tenant_id,owner_user_id,session_id,run_id,consent_epoch,erase_epoch,model_version,job_type,plan_json,generation_key,available_at,created_at) VALUES(?,'missing-account','deleted-owner',?,?,0,0,'orphan','CONSOLIDATE','{}',?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),UTC_TIMESTAMP(6))",orphan,UUID.randomUUID().toString(),UUID.randomUUID().toString(),orphan);
            Map<String,Object> claimed=ReflectionTestUtils.invokeMethod(a,"claim");assertNotNull(claimed);assertNotEquals(orphan,claimed.get("job_id"));
            db.update("DELETE FROM agent_memory_job WHERE job_id=?",orphan);
            assertNull(ReflectionTestUtils.invokeMethod(b,"claim"));
            extract(claimed,true);
            Map<String,Object> trailing=ReflectionTestUtils.invokeMethod(b,"claim");assertNotNull(trailing);extract(trailing,true);
            verify(model,times(1)).evaluate(anyString(),anyMap(),anyInt(),any());
        }finally{pool.shutdown();}
    }
    @Test void missingModelAtAcceptanceIsRecoveredWithoutDroppingTheMessage(){
        when(model.version()).thenReturn("");var r=run("我是天津大学的",null);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_window WHERE tenant_id=?",Integer.class,owner.tenantId()));
        when(model.version()).thenReturn("learning-test-v1");learning.recover(model.version());learning.recover(model.version());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_outbox WHERE tenant_id=? AND destination='MEMORY_CONSOLIDATE'",Integer.class,owner.tenantId()));
        extract(job(r,false),true);assertEquals(1,store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().size());
    }
    @Test void persistedSourcePlanCanResumeInANewServiceInstanceAfterProviderFailure(){
        var r=run("我是天津大学的",null);var j=job(r,false);
        doThrow(new AgentException("MODEL_UNAVAILABLE","injected outage")).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        assertThrows(AgentException.class,()->learning.work(j,model));assertTrue(json.valueToTree(j).path("plan_json").asText().contains("manifest"));
        doAnswer(inv->response(inv.getArgument(1),true)).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        var restarted=new MemoryConsolidation(source,json,ledger,store,beans.getBean(io.opentelemetry.api.trace.Tracer.class));
        restarted.work(new HashMap<>(db.queryForMap("SELECT * FROM agent_memory_job WHERE job_id=?",j.get("job_id"))),model);
        assertEquals("DONE",db.queryForObject("SELECT state FROM agent_memory_window WHERE run_id=?",String.class,r.id.toString()));
    }

    @Test void lateOutboxDeliveryAfterSessionDeletionCannotRecreateLearningJobs(){
        var r=run("我是天津大学的",null);var events=db.queryForList("SELECT * FROM agent_outbox WHERE tenant_id=? AND destination='MEMORY_CONSOLIDATE'",owner.tenantId());
        runs.deleteSession(r.sessionId,owner);var pool=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();pool.initialize();
        try{var worker=worker(pool);for(var event:events)worker.accept(event);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_memory_job WHERE tenant_id=?",Integer.class,owner.tenantId()));}finally{pool.shutdown();}
    }

    @Test void explicitBackgroundDeclarationSurvivesNaturalMessageRetentionButNotExplicitDeletion(){
        var r=run("请记住我来自天津大学",null);learning.requestSave(r);extract(job(r,true),true);done(r);
        var entry=store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items().get(0);
        db.update("UPDATE agent_message SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=?",r.id.toString());
        assertNotNull(store.get(owner,entry.id()));
        runs.deleteSession(r.sessionId,owner);assertThrows(AgentException.class,()->store.get(owner,entry.id()));
    }

    @Test void dreamRetryCannotForgetTheExpiredSourceOfItsEarlierPrivateTrace(){
        var first=run("我偏好简短回答",null);done(first);var old=auto(first,"偏好简短回答","old");
        var second=run("回答尽量简短",null);done(second);auto(second,"偏好简短回答","target");var j=dreamJob();
        doThrow(new AgentException("MODEL_ERROR","injected first attempt failure")).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        assertThrows(AgentException.class,()->dream.work(j,model));
        db.update("UPDATE agent_memory_source SET source_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE origin_run_id=?",first.id.toString());
        dream.work(j,model);
        assertEquals("SUCCEEDED",db.queryForObject("SELECT state FROM agent_memory_job WHERE job_id=?",String.class,j.get("job_id")));
        assertThrows(AgentException.class,()->ledger.verifyJob(second,owner,UUID.fromString(j.get("job_id").toString())));
    }

    @Test @EnabledIfEnvironmentVariable(named="AGENT_MEMORY_MODEL_IT",matches="true")
    void realModelJourneyWritesUpdatesAndRecallsAcrossSessions()throws Exception{
        String name=System.getenv("AGENT_MEMORY_MODEL_NAME");var config=new com.tianmu.agent.model.deepseek.DeepSeekProperties(java.net.URI.create(System.getenv("AGENT_MEMORY_MODEL_BASE_URL")),System.getenv("AGENT_MEMORY_MODEL_KEY"),name,name,java.time.Duration.ofSeconds(3),384,3072,name,false);
        var real=new com.tianmu.agent.model.deepseek.DeepSeekMemoryModel(config,json,com.tianmu.agent.model.ContextPolicy.defaults(),beans.getBean(io.opentelemetry.api.trace.Tracer.class));
        when(model.version()).thenReturn(real.version());
        doAnswer(inv->real.evaluate(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3))).when(model).evaluate(anyString(),anyMap(),anyInt(),any());
        var first=run("我来自天津大学。以后回答请简短一点。",null);done(first);learning.work(job(first,false),model);
        var before=store.list(owner,Scope.user(),Long.MAX_VALUE,20,"").items();assertTrue(before.stream().anyMatch(e->e.content().kind()==Kind.PROFILE && e.content().text().contains("天津大学")));
        var school=before.stream().filter(e->e.content().kind()==Kind.PROFILE).findFirst().orElseThrow();
        var consumer=run("我学校附近有什么好玩的",null);recall.begin(consumer);recall.recall(consumer);assertTrue(consumer.memory().persistent().stream().anyMatch(e->e.id().equals(school.id())));done(consumer);
        var correction=run("请记住，以后回答详细一点。",first.sessionId);learning.requestSave(correction);learning.work(job(correction,true),model);
        var facts=db.queryForList("SELECT r.content,r.version FROM agent_memory_fact f JOIN agent_memory_revision r ON r.tenant_id=f.tenant_id AND r.owner_user_id=f.owner_user_id AND r.memory_id=f.memory_id AND r.version=f.memory_version WHERE f.tenant_id=? AND JSON_UNQUOTE(JSON_EXTRACT(f.identity_json,'$.attribute'))='response_length'",owner.tenantId());
        assertEquals(1,facts.size());assertTrue(facts.get(0).get("content").toString().contains("详细"));assertEquals(2,((Number)facts.get(0).get("version")).intValue());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/memory-real-journey.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("model",name,"syntheticMaterials",true,"crossSessionRecall",true,"updatedVersion",2,"backgroundCalls",2)));
    }

    @Test void disablingCoreInjectionStillAllowsAnExplicitlyRelatedPreference(){
        var receipt=store.create(owner,new Content(Scope.user(),Kind.PREFERENCE,"用户偏好简短回答",null),"core-off");
        db.update("INSERT INTO agent_memory_index(tenant_id,owner_user_id,memory_id,version,is_core,importance) VALUES(?,?,?,?,1,50)",owner.tenantId(),owner.userId(),receipt.id().toString(),receipt.version());
        Object previous=ReflectionTestUtils.getField(recall,"policy");
        ReflectionTestUtils.setField(recall,"policy",new MemoryPolicyProperties(3000,30000,8,24,5,40,0,0,2));
        try{var r=run("按我的回答偏好介绍一下",null);recall.begin(r);recall.recall(r);assertEquals(1,r.memory().persistent().size());assertEquals(receipt.id(),r.memory().persistent().get(0).id());}
        finally{ReflectionTestUtils.setField(recall,"policy",previous);}
    }

    @Test void existingJobCannotFinishSuccessfullyAfterItsSessionWasDeleted(){
        var r=run("我是天津大学的",null);var j=job(r,false);runs.deleteSession(r.sessionId,owner);
        assertEquals("MEMORY_SOURCE_UNAVAILABLE",assertThrows(AgentException.class,()->learning.work(j,model)).code());
        verify(model,never()).evaluate(anyString(),anyMap(),anyInt(),any());
    }

}
