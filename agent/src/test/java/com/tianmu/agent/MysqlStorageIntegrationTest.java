package com.tianmu.agent;

import com.tianmu.agent.adapters.storage.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.RunService;
import com.tianmu.agent.runtime.RequestOrchestrator;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.runtime.control.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real MySQL/Redis integration. Refuses URLs outside a database ending in _it. */
@EnabledIfEnvironmentVariable(named="AGENT_STORAGE_IT", matches="true")
class MysqlStorageIntegrationTest {
    @Test void representativeMaterialsAndReportSurviveTwoWaitingRestartsWithoutRepeatingCalls()throws Exception {representativeRecovery(false);}
    @Test void fiveBucketsAndUnclassifiedSurviveTwoWaitingRestartsWithoutRepeatingCalls()throws Exception {representativeRecovery(true);}
    private void representativeRecovery(boolean five)throws Exception {
        var exports=new java.util.concurrent.atomic.AtomicInteger();var embeddings=new java.util.concurrent.atomic.AtomicInteger();var modelCalls=new java.util.concurrent.atomic.AtomicInteger();
        var policy=new com.tianmu.agent.feedback.FeedbackCompressionProperties(true,3,15000,.90,32,.75,2,3,60,.96,300,600,32768,67108864,512);
        com.tianmu.agent.feedback.FeedbackEmbedding embedding=new com.tianmu.agent.feedback.FeedbackEmbedding(){public String version(){return "sql-fixture-v1";}public int dimension(){return 3;}public List<List<Float>> embed(List<String> texts,com.tianmu.agent.runtime.control.CallControl c){embeddings.incrementAndGet();return texts.stream().map(t->List.of(1f,0f,0f)).toList();}};
        var vectors=mock(com.tianmu.agent.feedback.FeedbackVectorIndex.class);when(vectors.dimension()).thenReturn(3);when(vectors.fetch(any(),anyList(),any())).thenReturn(Map.of());
        com.tianmu.agent.feedback.FeedbackSnapshotPort snapshotSource=new com.tianmu.agent.feedback.FeedbackSnapshotPort(){
            public void authorize(AccessScope caller,long id){if(!caller.tenantId().equals(owner.tenantId()) || !caller.userId().equals(owner.userId()) || !caller.videoIds().contains(id))throw new AgentException("FORBIDDEN","fixture");}
            public Receipt export(FeedbackAnalysis.Request request,AccessScope caller,com.tianmu.agent.runtime.control.CallControl control,java.util.function.Consumer<List<FeedbackAnalysis.Unit>> sink){
                authorize(caller,request.query().videoId());exports.incrementAndGet();var at=request.query().end().minusSeconds(1).atZone(DanmakuTypes.ZONE).toInstant();
                var rows=new ArrayList<>(List.of(new FeedbackAnalysis.Unit("C:1",FeedbackAnalysis.Source.COMMENT,"1","背景音乐盖住人声了",five?"":"父评论说明了播放场景",false,null,"viewer1",at,100L),new FeedbackAnalysis.Unit("C:2",FeedbackAnalysis.Source.COMMENT,"2","音乐太大听不清楚",five?"":"父评论说明了播放场景",false,null,"viewer2",at,1L),new FeedbackAnalysis.Unit("D:3",FeedbackAnalysis.Source.DANMAKU,"3","手机外放听不清楚","",false,1000L,"viewer3",at,null)));
                if(five)rows.add(new FeedbackAnalysis.Unit("C:4",FeedbackAnalysis.Source.COMMENT,"4","完全同意","其他父评论",false,null,"viewer4",at,0L));sink.accept(rows);
                return new Receipt(Instant.now(),new FeedbackAnalysis.Counts(five?3:2,1,0,0,five?4:3));
            }
        };
        com.tianmu.agent.feedback.FeedbackModels models=new com.tianmu.agent.feedback.FeedbackModels(){
            public String modelName(){return "sql-fixture";}public String policyHash(){return "sql-fixture-v1";}
            public List<Proposal> analyze(Input input,com.tianmu.agent.runtime.control.CallControl control){
                modelCalls.incrementAndGet();com.tianmu.agent.runtime.control.ModelBudget.execute(control,"sql-fixture",true,8192,()->mapper.createObjectNode().set("usage",mapper.createObjectNode().put("prompt_tokens",100).put("completion_tokens",40)));
                var ids=input.kind()==FeedbackAnalysis.WorkKind.CLASSIFY?input.groups().stream().map(Group::id).toList():input.children().stream().map(FeedbackAnalysis.Topic::id).toList();return List.of(new Proposal("T1","音频清晰度","代表反馈提到配乐与人声的平衡。","建议检查音量。",ids));
            }
        };
        record Fixture(com.tianmu.agent.feedback.FeedbackAnalysisService analysis,com.tianmu.agent.feedback.FeedbackPreparationService preparation){}
        java.util.function.Supplier<Fixture> fixture=()->{
            var jobs=new MysqlFeedbackJobStore(source,mapper,()->repository.verifyReady());var work=new com.tianmu.agent.adapters.storage.MysqlFeedbackPreparationStore(source,mapper,()->repository.verifyReady());
            var objects=new com.tianmu.agent.feedback.FeedbackObjects(new FileObjectStore(directory),mapper,new com.tianmu.agent.adapters.storage.MysqlFeedbackObjectIndex(source,()->repository.verifyReady()));
            var service=new com.tianmu.agent.feedback.FeedbackAnalysisService(jobs,objects,snapshotSource,models,com.tianmu.agent.feedback.FeedbackPolicy.defaults());
            var prep=new com.tianmu.agent.feedback.FeedbackPreparationService(service,work,objects,com.tianmu.agent.feedback.FeedbackPolicy.defaults(),new com.tianmu.agent.feedback.FeedbackClusters(null,null),com.tianmu.agent.feedback.FeedbackRuntimeProperties.defaults(),new com.tianmu.agent.feedback.FeedbackCompression(embedding,vectors,policy,mapper));return new Fixture(service,prep);
        };
        var run=create("只根据视频1001最近30天的评论和弹幕生成报告，不读取视频内容");run.start();run.configureBudget(LoopPolicy.defaults());
        var spec=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve(run.query(),run.businessDate,run.createdAt);
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.NEUTRAL,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec),null,null));
        var assembler=new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog());org.springframework.test.util.ReflectionTestUtils.invokeMethod(assembler,"compressionPolicy",policy);run.task(assembler.assemble(run));
        var current=com.tianmu.agent.application.FeedbackTaskPolicy.compressedRequest(run.task().specification());var request=five?current:new FeedbackAnalysis.Request(current.query(),current.sources(),current.grounding(),current.videoStartMs(),current.videoEndMs(),com.tianmu.agent.feedback.FeedbackCompression.VERSION,current.forceReanalysis());var first=fixture.get();var job=first.preparation().requestMaterials(run.id,owner,request,run.deadline());
        var materialOp=PendingOperation.bounded(job.id(),"feedback-materials",job.deadline(),run.deadline());run.execution().observe(new ToolCall("prepare_feedback_materials",Map.of(),"material-call","material-group","","先筛选代表。"),new ToolResult("prepare_feedback_materials",Map.of("status","PENDING"),null,null,materialOp));run.waitFor(materialOp);
        for(int n=0;n<8 && !first.preparation().materialsReady(job.id());n++)for(var step:first.preparation().pending(job.id()))first.preparation().advance(job.id(),step,new com.tianmu.agent.runtime.control.CancellationToken());
        assertEquals(2,embeddings.get());repository.close();repository=new MysqlRunRepository(source,mapper,cache,new FileObjectStore(directory),Duration.ofSeconds(30),16);
        var recovered=repository.recoverWaiting().stream().filter(r->r.id.equals(run.id)).findFirst().orElseThrow();var second=fixture.get();assertEquals(Status.WAITING_OPERATION,recovered.snapshot().status());
        var control=new com.tianmu.agent.runtime.control.CallControl(recovered.deadline(),recovered.cancellation());var material=second.preparation().materials(job.id(),owner,control);assertEquals(five?3:2,material.representativeCount());assertEquals(five?1:0,material.unclassified().size());assertNotNull(material.stats());assertEquals(five?4:3,material.stats().daily().values().stream().mapToLong(Long::longValue).sum());assertEquals(1L,material.stats().playback().get(1000L));
        var result=com.tianmu.agent.application.FeedbackMaterialEvidence.bind(recovered,second.analysis().forJob(job.id()),material);assertTrue(recovered.completeOperation(materialOp,result));
        second.preparation().startCompressedReport(job.id(),owner,null,control);var reportOp=PendingOperation.bounded(job.id(),"representative-report",job.deadline(),recovered.deadline());recovered.execution().observe(new ToolCall("generate_feedback_report",Map.of("materials_id",result.evidenceId()),"report-call","report-group"),new ToolResult("generate_feedback_report",Map.of("status","PENDING"),null,null,reportOp));recovered.waitFor(reportOp);
        for(int n=0;n<12 && second.analysis().forJob(job.id()).phase()!=FeedbackAnalysis.Phase.READY;n++)second.analysis().advance(job.id());
        assertEquals(FeedbackAnalysis.Phase.READY,second.analysis().forJob(job.id()).phase());assertEquals(1,modelCalls.get());assertEquals(1,exports.get());
        repository.close();repository=new MysqlRunRepository(source,mapper,cache,new FileObjectStore(directory),Duration.ofSeconds(30),16);var finalRun=repository.recoverWaiting().stream().filter(r->r.id.equals(run.id)).findFirst().orElseThrow();var third=fixture.get();
        var reportJson=third.preparation().reportJson(job.id(),owner,new com.tianmu.agent.runtime.control.CallControl(finalRun.deadline(),finalRun.cancellation()));
        assertEquals(1,reportJson.playback().plotted());assertEquals(five?4:3,reportJson.daily().values().stream().mapToLong(Long::longValue).sum());
        var asset=third.analysis().asset(job.id(),owner,request);var evidence=com.tianmu.agent.application.FeedbackEvidenceBinding.bind(finalRun,asset);var receipt=new com.tianmu.agent.application.ReportArtifacts(repository).save(finalRun,evidence.id(),reportJson.html());
        assertTrue(finalRun.completeOperation(reportOp,new ToolResult("generate_feedback_report",Map.of("status","READY","url",receipt.url()),evidence.id())));
        assertInstanceOf(com.tianmu.agent.runtime.spi.CompletionEvaluator.Deliver.class,new com.tianmu.agent.application.BusinessCompletionEvaluator().evaluate(com.tianmu.agent.runtime.AgentRuntime.completionInput(finalRun)));
        assertEquals(2,embeddings.get());assertEquals(1,modelCalls.get());assertEquals(1,db.queryForObject("SELECT model_calls FROM agent_feedback_job WHERE job_id=?",Integer.class,job.id().toString()));assertEquals(2,db.queryForObject("SELECT embedding_calls FROM agent_feedback_preparation WHERE job_id=? AND step='SNAPSHOT'",Integer.class,job.id().toString()));
        assertEquals(reportJson,third.preparation().storedReportJson(job.id(),owner));assertTrue(repository.report(run.id,owner,receipt.artifactId()).length>0);finalRun.cancel();
    }
    @Test void changedReportResourcesRejectPriorManifestWithoutInventingContentHashes() throws Exception {
        var fixture=mapper.readTree(getClass().getResourceAsStream("/compat/agent-59a1746-resource-manifest.json"));
        Map<String,String> resources=mapper.convertValue(fixture.get("resourceVersions"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});
        Set<String> tools=mapper.convertValue(fixture.get("allowedTools"),new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){});
        var run=create("视频1001的评论报告，不读取视频内容");run.start();run.configureBudget(LoopPolicy.defaults());
        var spec=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve(run.query(),run.businessDate,run.createdAt);
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.NEUTRAL,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec),null,null));
        var assembler=new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog());var task=assembler.assemble(run);
        run.task(new com.tianmu.agent.core.TaskTypes.PreparedTask(task.runId(),task.originalQuery(),task.query(),task.businessDate(),task.timezone(),task.scope(),task.preparation(),task.specification(),resources,tools,task.deadline(),task.maxModelCalls()));
        var op=new PendingOperation(UUID.randomUUID(),"feedback-analysis",run.deadline());run.execution().observe(new ToolCall("analyze_feedback",Map.of()),new ToolResult("analyze_feedback",Map.of("status","PENDING"),null,null,op));run.waitFor(op);
        repository.close();repository=new MysqlRunRepository(source,mapper,cache,new FileObjectStore(directory),Duration.ofSeconds(30),16);
        var restored=repository.recoverWaiting().stream().filter(value->value.id.equals(run.id)).findFirst().orElseThrow();
        assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->assembler.verify(restored.task())).code());assertEquals(resources,restored.task().resourceVersions());assertNull(restored.executionPolicy());
        assertFalse(restored.task().resourceVersions().containsKey("prompt-content:harness.system"));restored.cancel();
    }
    @Test void versionedWaitingSurvivesRestartAndWriterRollbackWithoutResettingUsage() {
        var factory=new com.tianmu.agent.config.ExecutionPolicyFactory(new com.tianmu.agent.config.PolicySnapshotProperties(true),LoopPolicy.defaults(),com.tianmu.agent.model.ContextPolicy.defaults(),
                new com.tianmu.agent.preparation.PreparationPolicy(Duration.ofMillis(300),Duration.ofSeconds(8)),com.tianmu.agent.feedback.FeedbackPolicy.defaults());
        org.springframework.test.util.ReflectionTestUtils.setField(repository,"policyFactory",factory);
        var run=create("视频1001的评论和弹幕报告，不读取视频内容");run.start();
        var spec=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve(run.query(),run.businessDate,run.createdAt);
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.NEUTRAL,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec),null,null));
        new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog()).assemble(run);
        ModelBudget.execute(run.callControl(),"fixture",false,()->mapper.createObjectNode());
        var op=new PendingOperation(UUID.randomUUID(),"feedback-analysis",run.deadline());
        run.execution().observe(new ToolCall("analyze_feedback",Map.of()),new ToolResult("analyze_feedback",Map.of("status","PENDING"),null,null,op));run.waitFor(op);
        var policy=run.executionPolicy();var budget=run.modelBudget().snapshot();
        repository.close();repository=new MysqlRunRepository(source,mapper,cache,new FileObjectStore(directory),Duration.ofSeconds(2),1);
        var off=new com.tianmu.agent.config.ExecutionPolicyFactory(new com.tianmu.agent.config.PolicySnapshotProperties(false),LoopPolicy.defaults(),com.tianmu.agent.model.ContextPolicy.defaults(),new com.tianmu.agent.preparation.PreparationPolicy(Duration.ofMillis(300),Duration.ofSeconds(8)),com.tianmu.agent.feedback.FeedbackPolicy.defaults());
        org.springframework.test.util.ReflectionTestUtils.setField(repository,"policyFactory",off);
        var restored=repository.recoverWaiting().stream().filter(value->value.id.equals(run.id)).findFirst().orElseThrow();assertEquals(Status.WAITING_OPERATION,restored.snapshot().status());
        assertEquals(policy,restored.executionPolicy());assertEquals(policy.contentHash(),restored.continuation().policyHash());assertEquals(budget,restored.modelBudget().snapshot());
        assertEquals(8192,budget.charged());assertEquals(1,budget.unknown());assertEquals(16,restored.modelCallLimit());assertEquals(run.deadline(),restored.deadline());
        assertTrue(restored.completeOperation(op,new ToolResult("analyze_feedback",Map.of("status","READY"),null)));restored.cancel();
        var legacy=create("writer rollback creates legacy only");assertNull(legacy.executionPolicy());legacy.cancel();
        assertEquals(policy,repository.load(run.id,owner).executionPolicy());
    }
    @Test void committedTaskBindingWithLostAcknowledgementCannotCorruptTerminalPolicy() {
        var factory=new com.tianmu.agent.config.ExecutionPolicyFactory(new com.tianmu.agent.config.PolicySnapshotProperties(true),LoopPolicy.defaults(),com.tianmu.agent.model.ContextPolicy.defaults(),
                new com.tianmu.agent.preparation.PreparationPolicy(Duration.ofMillis(300),Duration.ofSeconds(8)),com.tianmu.agent.feedback.FeedbackPolicy.defaults());
        org.springframework.test.util.ReflectionTestUtils.setField(repository,"policyFactory",factory);
        var run=create("视频1001的评论和弹幕报告，不读取视频内容");run.start();
        var spec=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve(run.query(),run.businessDate,run.createdAt);
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.NEUTRAL,Set.of(),Set.of(),Intent.DANMAKU_REPORT,spec),null,null));
        var backend=(com.tianmu.agent.runtime.store.RunBackend)org.springframework.test.util.ReflectionTestUtils.getField(run,"backend");
        org.springframework.test.util.ReflectionTestUtils.setField(run,"backend",new com.tianmu.agent.runtime.store.RunBackend(){
            public void save(RunState value){backend.save(value);throw new AgentException("STORAGE_UNAVAILABLE","Acknowledgement lost after commit");}
            public void checkpoint(RunState value){backend.checkpoint(value);}
            public Snapshot commit(RunState value,Snapshot proposed,String event,Object data,Object done){return backend.commit(value,proposed,event,data,done);}
        });
        var assembler=new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog());
        assertThrows(AgentException.class,()->assembler.assemble(run));assertNull(run.task());
        var committed=repository.load(run.id,owner);assertNotNull(committed.task());assertEquals("FEEDBACK",committed.executionPolicy().windowReason());
        run.fail("STORAGE_UNAVAILABLE");
        var restored=repository.load(run.id,owner);assertEquals(Status.FAILED,restored.snapshot().status());
        assertEquals(committed.task(),restored.task());assertEquals(committed.executionPolicy(),restored.executionPolicy());assertEquals(committed.deadline(),restored.deadline());
        assertEquals(restored.executionPolicy(),run.executionPolicy());
        assertEquals(1,restored.events().after(0).events().stream().filter(e->e.type().equals("DONE")).count());
    }
    @Test void policyWriterCapturesInCreateWhileLegacyRecordsStayLegacy() {
        var legacy=create("legacy queued");assertNull(legacy.executionPolicy());
        var factory=new com.tianmu.agent.config.ExecutionPolicyFactory(new com.tianmu.agent.config.PolicySnapshotProperties(true),LoopPolicy.defaults(),com.tianmu.agent.model.ContextPolicy.defaults(),
                new com.tianmu.agent.preparation.PreparationPolicy(Duration.ofMillis(300),Duration.ofSeconds(8)),com.tianmu.agent.feedback.FeedbackPolicy.defaults());
        org.springframework.test.util.ReflectionTestUtils.setField(repository,"policyFactory",factory);
        var fresh=create("captured queued");var policy=fresh.executionPolicy();assertNotNull(policy);policy.verify();
        var reloaded=repository.load(fresh.id,owner);assertEquals(policy,reloaded.executionPolicy());assertEquals(policy.effectiveDeadline(),reloaded.deadline());
        assertNull(repository.load(legacy.id,owner).executionPolicy());
        fresh.start();fresh.cancel();assertEquals(policy,repository.load(fresh.id,owner).snapshot().executionPolicy());
        legacy.cancel();assertFalse(db.queryForObject("SELECT snapshot_json FROM agent_run WHERE tenant_id=? AND run_id=?",String.class,owner.tenantId(),legacy.id.toString()).contains("executionPolicy"));
    }
    @Test void reportClarificationResumesBothSourcesAndTwoDateBounds() {
        var original=create("视频1001的评论和弹幕报告，不读取视频内容");original.start();original.clarify("请补充时间范围");
        var day=LocalDate.now(com.tianmu.agent.core.DanmakuTypes.ZONE);String reply="从"+day.minusDays(3)+"到"+day.minusDays(1);
        var resumed=repository.create(reply,owner,original.sessionId,UUID.randomUUID().toString(),original.id,null).run();sessions.add(resumed.sessionId);
        var spec=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve(resumed.query(),resumed.businessDate,resumed.createdAt);
        assertEquals("audience.report",spec.type());assertEquals(Set.of(com.tianmu.agent.core.AnalysisContract.Source.COMMENT,com.tianmu.agent.core.AnalysisContract.Source.DANMAKU),spec.reading().sources());
        assertEquals(day.minusDays(3).atStartOfDay().toString(),spec.conditions().get("start"));assertEquals(day.minusDays(1).atStartOfDay().toString(),spec.conditions().get("end"));resumed.cancel();
    }
    @Test void pinnedModelAndStreamingEventsSurviveReload() {
        var choice=new com.tianmu.agent.model.RuntimeModelCatalog.Choice("fast","Fast","compatible-chat","v1");
        var created=repository.create("stream test",owner,null,"stream-model",null,null,choice);var run=created.run();sessions.add(run.sessionId);
        assertEquals(choice,run.modelChoice());run.start();
        run.emit("MODEL_SELECTED",choice);run.emit("TEXT_START",Map.of());run.emit("TEXT_DELTA",Map.of("text","你好"));run.emit("TEXT_END",Map.of());
        assertEquals(choice,repository.load(run.id,owner).modelChoice());
        var events=repository.load(run.id,owner).events().after(0).events();assertTrue(events.stream().anyMatch(e->e.type().equals("TEXT_DELTA")));
        var changed=new com.tianmu.agent.model.RuntimeModelCatalog.Choice("fast","Fast","compatible-v2","v2");
        assertEquals(choice,repository.create("stream test",owner,null,"stream-model",null,null,changed).run().modelChoice());
        assertThrows(AgentException.class,()->repository.create("stream test",owner,null,"stream-model",null,null,new com.tianmu.agent.model.RuntimeModelCatalog.Choice("other","Other","other","v1")));
        run.cancel();
    }
    @Test void videoIndexPersistsAndRejectsStaleWritersAcrossStoreInstances(){
        var first=new com.tianmu.agent.video.index.MysqlVideoIndexStore(source,null,mapper);
        var second=new com.tianmu.agent.video.index.MysqlVideoIndexStore(source,null,mapper);
        var now=Instant.now();var job=new com.tianmu.agent.video.index.VideoIndex(UUID.randomUUID(),1001,"v1",10000,"RUNNING",0,1,List.of(),null,now,now.plusSeconds(60));
        try{
            assertEquals(job,first.start(owner,job));assertEquals(job,second.get(owner,1001));
            assertNull(first.get(new AccessScope(owner.tenantId(),"other",Set.of(1001L)),1001));
            var cancelled=job.state("CANCELLED",List.of(),null);assertTrue(second.replace(owner,job,cancelled));
            assertFalse(first.replace(owner,job,job.state("READY",List.of(),null)));
            var retry=new com.tianmu.agent.video.index.VideoIndex(UUID.randomUUID(),1001,"v1",10000,"QUEUED",0,1,List.of(),null,now,now.plusSeconds(60));
            assertEquals(retry,first.start(owner,retry));assertEquals(retry,second.start(owner,job));
        }finally{db.update("DELETE FROM agent_video_index WHERE tenant_id=? AND owner_user_id=?",owner.tenantId(),owner.userId());}
    }
    @Test void sourcePreparationLedgerFencesRetriesAndPersistsSharedEmbeddingBudget(){
        var run=create("来源准备");var jobs=new MysqlFeedbackJobStore(source,mapper,repository::verifyReady);
        var now=Instant.now();var request=new FeedbackAnalysis.Request(new DanmakuTypes.Query(1001,LocalDate.now().minusDays(1).atStartOfDay(),LocalDate.now().atStartOfDay()),Set.of(FeedbackAnalysis.Source.COMMENT),FeedbackAnalysis.Grounding.FEEDBACK_ONLY,null,null,com.tianmu.agent.feedback.FeedbackPreparationService.VERSION);
        var job=jobs.create(new com.tianmu.agent.feedback.FeedbackJobStore.Job(UUID.randomUUID(),run.id,owner,request,FeedbackAnalysis.Phase.QUEUED,now,now.plusSeconds(120),20000,3,0,0,"fixture","hash",null,null,null,null,0,0));
        var ledger=new MysqlFeedbackPreparationStore(source,mapper,repository::verifyReady);
        var step=com.tianmu.agent.feedback.FeedbackPreparationStore.Step.SNAPSHOT;
        var trigger=new com.tianmu.agent.feedback.FeedbackPreparationStore.Reference("1".repeat(32),"2".repeat(16));
        var accepted=new com.tianmu.agent.feedback.FeedbackPreparationStore.Reference("1".repeat(32),"3".repeat(16));
        ledger.request(job.id(),step,trigger);ledger.request(job.id(),step);
        var first=ledger.claim(job.id(),step,now,Duration.ofSeconds(30));assertNotNull(first);
        assertNull(ledger.claim(job.id(),step,now,Duration.ofSeconds(30)));
        ledger.reserveEmbedding(job.id(),1);
        db.update("UPDATE agent_feedback_preparation SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE job_id=?",job.id().toString());
        var rebuilt=new MysqlFeedbackPreparationStore(source,mapper,repository::verifyReady);
        var second=rebuilt.claim(job.id(),step,Instant.now(),Duration.ofSeconds(30));assertEquals(first.fence()+1,second.fence());
        var blob=new FeedbackAnalysis.Blob("fb_"+UUID.randomUUID().toString().replace("-","")+".json",2,"0".repeat(64));
        assertFalse(ledger.complete(first,blob,trigger));assertTrue(rebuilt.complete(second,blob,accepted));
        assertEquals(blob,ledger.get(job.id(),step).result());assertEquals(trigger,rebuilt.get(job.id(),step).triggeredBy());assertEquals(accepted,ledger.get(job.id(),step).acceptedBy());assertNull(ledger.claim(job.id(),step,now,Duration.ofSeconds(30)));
        var child=com.tianmu.agent.feedback.FeedbackPreparationStore.Step.COMMENT;ledger.request(job.id(),child);
        var stale=ledger.claim(job.id(),child,now,Duration.ofSeconds(30));rebuilt.settle(job.id(),"FAILED");
        assertEquals("COMPLETE",ledger.get(job.id(),step).state());assertEquals("FAILED",ledger.get(job.id(),child).state());
        assertFalse(ledger.complete(stale,blob));assertNull(ledger.claim(job.id(),child,Instant.now().plusSeconds(60),Duration.ofSeconds(30)));
        assertEquals("EMBEDDING_BUDGET_EXCEEDED",assertThrows(AgentException.class,()->rebuilt.reserveEmbedding(job.id(),1)).code());
    }
    @Test void lookupAndSavedReportProvenanceSurviveReload(){
        var run=create("historical video report");run.start();
        String id="ev_"+UUID.randomUUID();
        var video=new VideoContent.Facts("video-content-v1",1001,"fixture",0,10000,"历史摘要",List.of(),"","SAMPLED_FRAMES",List.of(1000L),"fixture",Instant.parse("2026-10-01T00:00:00Z"));
        run.execution().evidence().put(new Evidence(id,run.id,owner,null,null,video));
        var artifact=repository.saveReport(run.id,owner,id,"<html>fixture</html>".getBytes());
        run.succeed(new Answer("已保存",List.of(id)));
        assertEquals(id,repository.describeReport(run.id,owner,artifact.artifactId()).evidenceId());
        var reader=new com.tianmu.agent.application.HistoricalReportReader(repository,repository,null);
        assertEquals("历史摘要",((Map<?,?>)reader.read(reader.source(run.id,artifact.artifactId(),owner,null),artifact.artifactId(),"summary",0,10).get("content")).get("summary"));
        db.update("UPDATE agent_artifact SET source_evidence_id=NULL WHERE artifact_id=?",artifact.artifactId());
        assertEquals("LEGACY_SINGLE_FINAL_EVIDENCE",repository.describeReport(run.id,owner,artifact.artifactId()).provenance());
        assertThrows(AgentException.class,()->reader.source(run.id,artifact.artifactId(),new AccessScope(owner.tenantId(),owner.userId(),Set.of(1002L)),null));
        var current=create("lookup roundtrip");current.start();String lookupId="ev_"+UUID.randomUUID();
        var facts=new LookupFacts("resource-lookup-v1","read_report",Map.of("source_run_id",run.id.toString(),"artifact_id",artifact.artifactId()),Set.of(1001L),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        current.execution().evidence().put(new Evidence(lookupId,current.id,owner,null,null,null,null,null,null,facts));
        assertEquals(facts,repository.load(current.id,owner).execution().evidence().get(lookupId).lookup());current.cancel();
    }
    @Test void feedbackEvidenceRoundTripsInMysqlAndAllowsTheWaitingRunToResume(){
        var run=create("反馈报告");run.start();run.configureBudget(LoopPolicy.defaults());
        var request=new FeedbackAnalysis.Request(new DanmakuTypes.Query(1001,LocalDate.now().minusDays(1).atStartOfDay(),LocalDate.now().atStartOfDay()),Set.of(FeedbackAnalysis.Source.COMMENT),FeedbackAnalysis.Grounding.FEEDBACK_ONLY,null,null,"feedback-analysis-v1");
        var op=new PendingOperation(UUID.randomUUID(),"feedback-analysis",run.deadline());
        run.execution().observe(new ToolCall("analyze_feedback",Map.of()),new ToolResult("analyze_feedback",Map.of("status","PENDING"),null,null,op));run.waitFor(op);
        var binding=new FeedbackAnalysis.Binding(op.id(),request,new FeedbackAnalysis.Counts(2,0,0,0,2),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),"0".repeat(64),"fixture","fixture");
        String id="ev_"+UUID.randomUUID();run.execution().evidence().put(new Evidence(id,run.id,owner,null,null,null,null,null,binding));
        var loaded=repository.load(run.id,owner);assertEquals(binding,loaded.execution().evidence().get(id).feedback());
        assertEquals(binding,loaded.execution().evidence().snapshot().get(id).feedback());
        assertTrue(run.completeOperation(op,new ToolResult("analyze_feedback",Map.of("status","READY"),id)));
        assertEquals(Status.RUNNING,repository.load(run.id,owner).snapshot().status());run.cancel();
    }
    @Test void pendingOperationSurvivesWriterRestartWithPairedCallAndBudget() {
        var run=create("后台反馈分析");run.start();
        run.configureBudget(LoopPolicy.defaults());
        var op=new PendingOperation(UUID.randomUUID(),"feedback-analysis",run.deadline());
        run.execution().activateSkill("audience-report");
        run.execution().observe(new ToolCall("analyze_feedback",Map.of(),"it-provider-call"),new ToolResult("analyze_feedback",Map.of("status","PENDING"),null,null,op));
        ModelBudget.execute(run.callControl(),"fixture",false,()->mapper.createObjectNode());
        run.waitFor(op);var budget=run.modelBudget().snapshot();repository.close();
        repository=new MysqlRunRepository(source,mapper,cache,new FileObjectStore(directory),Duration.ofSeconds(30),16);
        var recovered=repository.recoverWaiting().stream().filter(r->r.id.equals(run.id)).findFirst().orElseThrow();
        assertEquals(Status.WAITING_OPERATION,recovered.snapshot().status());assertEquals(budget,recovered.modelBudget().snapshot());assertTrue(budget.charged()>0);
        assertEquals("it-provider-call",recovered.execution().exchanges().get(0).call().providerCallId());
        assertTrue(recovered.completeOperation(op,new ToolResult("analyze_feedback",Map.of("status","READY"),"ev_it")));
        assertEquals(Status.RUNNING,repository.load(run.id,owner).snapshot().status());recovered.cancel();
    }
    @Test void feedbackSqlFencesLateResultsAndKeepsUnknownSpendingAcrossClaims() {
        var run=create("反馈任务");var store=new MysqlFeedbackJobStore(source,mapper,repository::verifyReady);
        var now=Instant.now();var request=new FeedbackAnalysis.Request(new DanmakuTypes.Query(1001,LocalDate.now().minusDays(1).atStartOfDay(),LocalDate.now().atStartOfDay()),Set.of(FeedbackAnalysis.Source.COMMENT),FeedbackAnalysis.Grounding.FEEDBACK_ONLY,null,null,"feedback-analysis-v1");
        var j=store.create(new com.tianmu.agent.feedback.FeedbackJobStore.Job(UUID.randomUUID(),run.id,owner,request,FeedbackAnalysis.Phase.QUEUED,now,now.plusSeconds(120),20000,3,0,0,"fixture","hash",null,null,null,null,0,0));
        var blob=new FeedbackAnalysis.Blob("fb_"+UUID.randomUUID().toString().replace("-","")+".json",2,"0".repeat(64));
        assertTrue(store.initialize(j.id(),j.version(),new FeedbackAnalysis.Snapshot("feedback-snapshot-v1",now,new FeedbackAnalysis.Counts(1,0,0,0,1),List.of(blob),"0".repeat(64)),null,List.of(blob)));
        db.update("UPDATE agent_feedback_work SET available_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 SECOND) WHERE job_id=?",j.id().toString());
        assertNull(store.claim(j.id(),Instant.now().plusSeconds(30),Duration.ofSeconds(30)),"Host clock cannot bypass database retry time");
        db.update("UPDATE agent_feedback_work SET available_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE job_id=?",j.id().toString());
        var first=store.claim(j.id(),Instant.now(),Duration.ofSeconds(30));assertNotNull(first);store.reserve(j.id(),first.id(),first.fence(),8192);
        assertThrows(AgentException.class,()->store.reserve(j.id(),first.id(),first.fence(),8192));
        db.update("UPDATE agent_feedback_work SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE work_id=?",first.id().toString());
        var second=store.claim(j.id(),Instant.now(),Duration.ofSeconds(30));assertTrue(second.fence()>first.fence());assertFalse(store.complete(first,blob));
        store.reserve(j.id(),second.id(),second.fence(),8192);assertEquals(16384,store.get(j.id()).chargedTokens());
        store.settle(j.id(),second.id(),second.fence(),100,20);assertEquals(8312,store.get(j.id()).chargedTokens());store.settle(j.id(),second.id(),second.fence(),100,20);assertEquals(8312,store.get(j.id()).chargedTokens());
        var correction=new FeedbackAnalysis.Blob("fb_"+UUID.randomUUID().toString().replace("-","")+".json",2,"1".repeat(64));
        assertFalse(store.retry(first,"INVALID_ANALYSIS",Instant.now().minusSeconds(1),3,correction));
        assertEquals(blob,store.work(second.id()).input(),"A stale worker cannot overwrite correction input");
        assertTrue(store.retry(second,"INVALID_ANALYSIS",Instant.now().minusSeconds(1),3,correction));
        var third=store.claim(j.id(),Instant.now(),Duration.ofSeconds(30));assertEquals(correction,third.input());
        store.cancel(j.id());assertFalse(store.complete(third,blob));
        var index=new MysqlFeedbackObjectIndex(source,repository::verifyReady);var objects=new com.tianmu.agent.feedback.FeedbackObjects(new FileObjectStore(directory),mapper,index);
        var real=objects.put(j.id(),Map.of("private","fixture"));assertTrue(java.nio.file.Files.exists(directory.resolve(real.key())));
        run.cancel();repository.deleteSession(run.sessionId,owner);repository.garbageCollect();objects.collectExpired();
        assertFalse(java.nio.file.Files.exists(directory.resolve(real.key())));assertNull(store.get(j.id()));
    }
    @Test void feedbackObservationRangesAreOrderedBoundedAndJobScoped(){
        var run=create("分页反馈");var store=new MysqlFeedbackJobStore(source,mapper,repository::verifyReady);
        var now=Instant.now();var request=new FeedbackAnalysis.Request(new DanmakuTypes.Query(1001,LocalDate.now().minusDays(1).atStartOfDay(),LocalDate.now().atStartOfDay()),Set.of(FeedbackAnalysis.Source.COMMENT),FeedbackAnalysis.Grounding.FEEDBACK_ONLY,null,null,"feedback-analysis-v1");
        var job=store.create(new com.tianmu.agent.feedback.FeedbackJobStore.Job(UUID.randomUUID(),run.id,owner,request,FeedbackAnalysis.Phase.QUEUED,now,now.plusSeconds(120),20000,3,0,0,"fixture","hash",null,null,null,null,0,0));
        var blob=new FeedbackAnalysis.Blob("fb_"+UUID.randomUUID().toString().replace("-","")+".json",2,"0".repeat(64));
        assertTrue(store.initialize(job.id(),job.version(),new FeedbackAnalysis.Snapshot("feedback-snapshot-v1",now,new FeedbackAnalysis.Counts(3,0,0,0,3),List.of(blob),"0".repeat(64)),null,List.of(blob,blob,blob)));
        for(int i=0;i<3;i++)assertTrue(store.complete(store.claim(job.id(),Instant.now(),Duration.ofSeconds(30)),blob));
        assertEquals(List.of(1,2),store.completedRange(job.id(),0,1,2).stream().map(com.tianmu.agent.feedback.FeedbackJobStore.Work::ordinal).toList());
        assertEquals(1,store.completedRange(job.id(),0,1,1).size());
        assertTrue(store.completedRange(job.id(),0,3,1).isEmpty());
        assertTrue(store.completedRange(job.id(),1,0,3).isEmpty());
        assertTrue(store.completedRange(UUID.randomUUID(),0,0,3).isEmpty());
        db.update("UPDATE agent_feedback_work SET state='PENDING' WHERE job_id=? AND ordinal_no=1",job.id().toString());
        assertEquals(List.of(2),store.completedRange(job.id(),0,1,2).stream().map(com.tianmu.agent.feedback.FeedbackJobStore.Work::ordinal).toList());
    }
    @TempDir Path directory;
    HikariDataSource source; MysqlRunRepository repository; RedisMemoryCache cache; JdbcTemplate db;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AccessScope owner; final List<UUID> sessions = new ArrayList<>();
    @BeforeEach void open() {
        String url = System.getenv("AGENT_JDBC_URL"); assertTrue(url.matches("jdbc:mysql://[^/]+/[^?]*_it(?:\\?.*)?"), "Integration requires a dedicated _it database");
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername(System.getenv("AGENT_DB_USER"));
        config.setPassword(System.getenv("AGENT_DB_PASSWORD")); config.setMaximumPoolSize(5); config.setConnectionInitSql("SET time_zone = '+00:00'");
        source = new HikariDataSource(config); db = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        cache = new RedisMemoryCache(mapper, true, System.getenv("AGENT_REDIS_HOST"), Integer.parseInt(System.getenv("AGENT_REDIS_PORT")), "");
        repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16);
        owner = new AccessScope("it_" + UUID.randomUUID().toString().substring(0, 8), "alice", Set.of(1001L, 1002L));
    }
    @AfterEach void close() {
        if (repository != null) {
            for (UUID session : sessions) { try { repository.deleteSession(session, owner); } catch (Exception ignored) {} }
            repository.garbageCollect(); repository.close();
        }
        if (cache != null) cache.close(); if (source != null) source.close();
    }
    RunState create(String query) { RunState run = repository.create(query, owner, null, UUID.randomUUID().toString(), null).run(); sessions.add(run.sessionId); return run; }
    @Test void historySearchAndPagingTraverseDatabaseWindowsWithoutIncludingTheActiveTurn() {
        var first=create("needle-first");faq(first);
        for(int i=1;i<=26;i++) {
            var older=repository.create(i==14?"needle-middle":"filler-"+i,owner,first.sessionId,UUID.randomUUID().toString(),null).run();faq(older);
        }
        var current=repository.create("needle-current",owner,first.sessionId,UUID.randomUUID().toString(),null).run();
        var service=new com.tianmu.agent.application.HistoryQueryService(repository,current.sessionId,owner,current.turnSeq);
        var recent=service.read(Long.MAX_VALUE,1,"needle");
        assertEquals(5L,recent.get("next_before"));assertEquals(true,recent.get("more_available"));
        assertTrue(recent.toString().contains("needle-middle"));assertFalse(recent.toString().contains("needle-current"));
        var oldest=service.read(5,1,"needle");assertTrue(oldest.toString().contains("needle-first"));assertEquals(false,oldest.get("more_available"));
        var seen=new ArrayList<Long>();long cursor=Long.MAX_VALUE;
        for(int page=0;page<12;page++) {
            var result=service.read(cursor,7,"");
            for(var raw:(List<?>)result.get("messages"))seen.add(((Number)((Map<?,?>)raw).get("sequence")).longValue());
            long next=((Number)result.get("next_before")).longValue();assertTrue(next<cursor);
            if(!Boolean.TRUE.equals(result.get("more_available")))break;cursor=next;
        }
        assertEquals(54,seen.size());assertEquals(54,new HashSet<>(seen).size());
        assertEquals(java.util.stream.LongStream.rangeClosed(1,54).boxed().toList(),seen.stream().sorted().toList());current.cancel();
    }
    @Test void historyQueryRedactsBeforeSearchAndRejectsForeignSessionsAndExpiredTurns() {
        var first=create("公开历史问题");faq(first);
        var narrow=new AccessScope(owner.tenantId(),owner.userId(),Set.of(1002L));
        var current=repository.create("current",narrow,first.sessionId,null,null).run();
        var service=new com.tianmu.agent.application.HistoryQueryService(repository,current.sessionId,narrow,current.turnSeq);
        assertTrue(((List<?>)service.read(Long.MAX_VALUE,4,"approved").get("messages")).isEmpty());
        assertTrue(service.read(Long.MAX_VALUE,4,"").toString().contains("已隐藏"));
        assertFalse(service.read(Long.MAX_VALUE,4,"").toString().contains("approved"));
        for(var foreign:List.of(new AccessScope(owner.tenantId(),"bob",owner.videoIds()),new AccessScope("other-"+owner.tenantId(),owner.userId(),owner.videoIds())))
            assertEquals("SESSION_NOT_FOUND",assertThrows(AgentException.class,()->new com.tianmu.agent.application.HistoryQueryService(repository,current.sessionId,foreign,current.turnSeq).read(Long.MAX_VALUE,4,"")).code());
        var sibling=create("sibling-only-history");faq(sibling);
        assertTrue(((List<?>)service.read(Long.MAX_VALUE,4,"sibling-only-history").get("messages")).isEmpty());
        db.update("UPDATE agent_run SET data_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=?",first.id.toString());
        assertTrue(((List<?>)new com.tianmu.agent.application.HistoryQueryService(repository,current.sessionId,owner,current.turnSeq).read(Long.MAX_VALUE,4,"").get("messages")).isEmpty());
        current.cancel();
    }
    @Test void historicalReportCheckpointRemainsReadableAfterReloadAndRequiresOwnership() {
        var run=create("report checkpoint");run.start();
        var q=new DanmakuTypes.Query(1001,run.businessDate.minusDays(1).atStartOfDay(),run.businessDate.atStartOfDay());
        var groups=List.of(new AudienceData.TextGroup("1","问题一",1,500L,false),new AudienceData.TextGroup("2","问题二",1,500L,false));
        var bins=java.util.stream.IntStream.range(0,12).mapToObj(i->new DanmakuTypes.Bucket(i,i==0?2:0)).toList();
        var data=new DanmakuTypes.Dataset(q,2,1,bins,List.of(),Instant.now(),"fixture",false,
                new AudienceData.Overview(List.of(),List.of(new AudienceData.Position(0,1000,2)),0,groups,2,2));
        String id="ev_"+UUID.randomUUID();run.execution().evidence().put(new Evidence(id,run.id,owner,null,data));
        var topic=new AudienceData.Topic("T1","讨论","原文","",List.of("1"),1,"",List.of());
        var first=new DanmakuClassification(1,List.of(topic),null,false);
        run.execution().evidence().put(new Evidence("ev_"+UUID.randomUUID(),run.id,owner,null,null,null,null,new ReportCheckpoint("audience-report-checkpoint-v1",id,first)));
        var loaded=repository.load(run.id,owner);
        assertEquals(first,loaded.execution().evidence().snapshot().values().stream().filter(e->e.reportCheckpoint()!=null).findFirst().orElseThrow().reportCheckpoint().classification());
        assertEquals(1,loaded.execution().evidence().snapshot().values().stream().filter(e->e.reportCheckpoint()!=null).count());
        assertEquals("RUN_NOT_FOUND",assertThrows(AgentException.class,()->repository.load(run.id,new AccessScope(owner.tenantId(),"bob",owner.videoIds()))).code());
        run.cancel();
    }
    @Test void conversationFocusForeignKeyColumnsMatchSessionCollations(){
        for(String column:List.of("tenant_id","owner_user_id","session_id")){
            String sessionCollation=db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_session' AND COLUMN_NAME=?",String.class,column);
            String focusCollation=db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_conversation_focus' AND COLUMN_NAME=?",String.class,column);
            assertNotNull(sessionCollation);assertEquals(sessionCollation,focusCollation,column);
        }
    }
    void faq(RunState run) {
        run.start(); run.prepared(new Result(Route.FAQ, new PreparedRequest(run.message, run.query(), Emotion.UNKNOWN, Set.of()), new FaqAnswer("fixture", "1", "approved"), null));
        assertTrue(run.succeed(run.reserveFinalResponse(), new Answer("approved", List.of()), StopReason.FAQ_ANSWER));
    }
    @Test void pageAndConversationFocusSurviveReloadWithoutMixingUserTextWithMetadata() {
        var page=new RequestContext("1001","author supplied title",1500L);
        var run=repository.create("下一个视频给我一点灵感",owner,null,UUID.randomUUID().toString(),null,page).run();sessions.add(run.sessionId);
        var focus=new MemoryTypes.Focus(run.message,run.id,"1001");repository.focus(run,focus);faq(run);
        var loaded=repository.load(run.id,owner);assertEquals(page,loaded.pageContext());assertEquals(run.message,loaded.query());
        var next=repository.create("就是现在的视频",owner,run.sessionId,UUID.randomUUID().toString(),null,page).run();
        assertEquals(focus,repository.memory(next).focus());
        repository.focus(next,new MemoryTypes.Focus("新目标",next.id,"1001"));
        repository.focus(run,focus);
        assertEquals(2L,db.queryForObject("SELECT turn_seq FROM agent_conversation_focus WHERE tenant_id=? AND session_id=?",Long.class,owner.tenantId(),run.sessionId.toString()));
        faq(next);
    }
    @Test void creatorDraftCasSurvivesReloadAndSessionDeletionRemovesIt() {
        var run = create("creator draft");
        var store = new MysqlCreatorDraftStore(source,mapper);
        var draft = new com.tianmu.agent.interaction.CreatorDraft(run.id,owner.tenantId(),owner.userId(),"1001","COMMENT","DRAFT",
                List.of(new com.tianmu.agent.interaction.CreatorDraft.Item("123","作者留言",null)));
        store.create(draft);
        assertEquals(draft,new MysqlCreatorDraftStore(source,mapper).get(run.id));
        var frozen = draft.with("PUBLISHING",draft.items());
        assertTrue(store.replace(draft,frozen)); assertFalse(store.replace(draft,frozen));
        assertEquals(frozen,store.get(run.id));
        faq(run); repository.deleteSession(run.sessionId,owner); repository.garbageCollect();
        assertNull(store.get(run.id));
    }
    @Test void frozenAnalysisFocusAndVideoOnlyArtifactSurviveDurableReload() {
        var run=create("生成视频1001的视频内容报告，只看视频内容");run.start();
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.UNKNOWN,Set.of()),null,null));
        run.task(new com.tianmu.agent.application.TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(),new com.tianmu.agent.prompt.PromptCatalog(),new com.tianmu.agent.tool.ToolCatalog()).assemble(run));
        var specification=run.task().specification();
        repository.focus(run,new MemoryTypes.Focus(run.message,run.id,"1001",specification));
        var facts=new VideoContent.Facts("video-content-v1",1001,"fixture",0,1000,"画面",List.of(new VideoContent.Moment(500,"场景")),"","SAMPLED_FRAMES",List.of(500L),"fixture",Instant.now());
        var evidence=new com.tianmu.agent.application.BusinessEvidencePolicy().registerVideoEvidence(run,facts);
        byte[] html="<!doctype html><html>video-only</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var ref=new com.tianmu.agent.application.ReportArtifacts(repository).save(run,evidence.id(),html);
        assertTrue(run.execution().hasReport(evidence.id()));
        assertTrue(run.succeed(run.reserveFinalResponse(),new Answer("report",List.of(evidence.id())),StopReason.TOOL_DELIVERY));
        var loaded=repository.load(run.id,owner);
        assertEquals(specification.analysis(),loaded.task().specification().analysis());
        assertEquals(specification.typed(),loaded.task().specification().typed());
        assertArrayEquals(html,repository.report(run.id,owner,ref.artifactId()));
        var next=repository.create("再加上评论",owner,run.sessionId,UUID.randomUUID().toString(),null,new RequestContext("1001")).run();
        var focus=repository.memory(next).focus();assertNotNull(focus.task());
        assertEquals(specification.analysis(),focus.task().analysis());assertEquals(specification.typed(),focus.task().typed());
        next.cancel();
    }
    @Test void retryIsIdempotentAndOwnershipIsCheckedOnRead() {
        String key = UUID.randomUUID().toString(); var first = repository.create("question", owner, null, key, null); sessions.add(first.run().sessionId);
        assertTrue(first.fresh()); assertEquals(first.run().id, repository.create("question", owner, null, key, null).run().id);
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(AgentException.class, () -> repository.create("different", owner, null, key, null)).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(AgentException.class, () -> repository.load(first.run().id, new AccessScope(owner.tenantId(), "bob", owner.videoIds()))).code());
        faq(first.run()); assertEquals(Status.SUCCEEDED, repository.load(first.run().id, owner).snapshot().status());
    }
    @Test void actualSqlFailureRollsBackMessageEventsAuditAndTerminalState() {
        RunState run = create("rollback"); run.start(); String trigger = "it_done_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON agent_run_event FOR EACH ROW BEGIN IF NEW.run_id='" + run.id + "' AND NEW.event_type='DONE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='integration terminal fault'; END IF; END");
        try {
            assertEquals("STORAGE_UNAVAILABLE", assertThrows(AgentException.class, () -> run.succeed(new Answer("candidate", List.of()))).code());
            assertEquals(Status.RUNNING, run.snapshot().status()); assertEquals(Status.RUNNING, repository.load(run.id, owner).snapshot().status());
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_message WHERE run_id=? AND role='ASSISTANT'", Integer.class, run.id.toString()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id=? AND event_type IN ('ANSWER','DONE')", Integer.class, run.id.toString()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_audit_event WHERE run_id=? AND action='RUN_COMMITTED'", Integer.class, run.id.toString()));
        } finally { db.execute("DROP TRIGGER " + trigger); }
        run.cancel(); assertEquals(Status.CANCELLED, repository.load(run.id, owner).snapshot().status());
    }
    String rejectDone(RunState run) {
        String trigger = "it_done_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON agent_run_event FOR EACH ROW BEGIN IF NEW.run_id='" + run.id + "' AND NEW.event_type='DONE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='integration terminal fault'; END IF; END");
        return trigger;
    }
    void maintainUntil(RunService service, java.util.function.BooleanSupplier completed) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!completed.getAsBoolean() && System.nanoTime() < until) { service.maintain(); Thread.sleep(25); }
        assertTrue(completed.getAsBoolean(), "Storage recovery must converge without another client request");
    }
    @Test void maintenanceRetriesOnlyTerminalTransactionAfterSqlOutageCrossesDeadline() throws Exception {
        repository.close();
        repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofMillis(600), 16);
        var orchestrator = mock(RequestOrchestrator.class);
        var service = new RunService(orchestrator, mock(RuntimeHarness.class), repository);
        var failed = new java.util.concurrent.CountDownLatch(1);
        var trigger = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var control = new LoopControl(LoopPolicy.defaults())) {
            doAnswer(invocation -> {
                RunState run = invocation.getArgument(0);
                try {
                    run.start(); run.modelCallStarted(); control.arm(run);
                    trigger.set(rejectDone(run)); run.fail("MODEL_ERROR");
                } catch (AgentException expected) { assertEquals("STORAGE_UNAVAILABLE", expected.code()); }
                finally { failed.countDown(); }
                return null;
            }).when(orchestrator).execute(any());
            RunState run = service.create("recovery", owner); sessions.add(run.sessionId);
            assertTrue(failed.await(3, java.util.concurrent.TimeUnit.SECONDS)); assertNotNull(trigger.get());
            Thread.sleep(Math.max(1, Duration.between(Instant.now(), run.deadline()).toMillis() + 100));
            assertEquals(Status.RUNNING, repository.load(run.id, owner).snapshot().status());
            assertEquals(1, service.pendingTerminals());
            assertEquals(StopReason.ERROR, run.cancellation().reason());
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id=? AND event_type='DONE'", Integer.class, run.id.toString()));
            db.execute("DROP TRIGGER " + trigger.getAndSet(null));
            maintainUntil(service, () -> AgentTypes.terminal(run.snapshot().status()));
            assertEquals(Status.FAILED, repository.load(run.id, owner).snapshot().status());
            assertEquals("MODEL_ERROR", run.snapshot().errorCode()); assertEquals(1, run.snapshot().modelCalls());
            assertEquals(0, service.pendingTerminals()); verify(orchestrator, times(1)).execute(run);
            service.maintain();
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id=? AND event_type='DONE'", Integer.class, run.id.toString()));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM agent_audit_event WHERE run_id=? AND action='RUN_COMMITTED'", Integer.class, run.id.toString()));
        } finally {
            if (trigger.get() != null) db.execute("DROP TRIGGER " + trigger.get());
            service.shutdown();
        }
    }
    @Test void mysqlRowLockOnOneTerminationDoesNotDelayAnotherWatchdogOrPublishAnEarlyDone() throws Exception {
        repository.close();
        repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(1), 16);
        RunState first = create("locked termination"), second = create("independent termination"); first.start(); second.start();
        var firstStopped = new java.util.concurrent.CountDownLatch(1); var firstEnded = new java.util.concurrent.CountDownLatch(1);
        var secondEnded = new java.util.concurrent.CountDownLatch(1);
        first.cancellation().onCancel(firstStopped::countDown); first.onTerminal(s -> firstEnded.countDown());
        second.onTerminal(s -> secondEnded.countDown());
        try (var lock = source.getConnection(); var control = new LoopControl(LoopPolicy.defaults())) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT run_id FROM agent_run WHERE tenant_id=? AND run_id=? FOR UPDATE")) {
                statement.setString(1, owner.tenantId()); statement.setString(2, first.id.toString());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            try {
                control.arm(first); control.arm(second);
                assertTrue(firstStopped.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(secondEnded.await(2, java.util.concurrent.TimeUnit.SECONDS), "The other run must finish while the first row lock is still held");
                assertEquals(1, firstEnded.getCount());
                assertEquals(Status.RUNNING, repository.load(first.id, owner).snapshot().status());
                assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id=? AND event_type='DONE'", Integer.class, first.id.toString()));
                assertEquals(Status.FAILED, repository.load(second.id, owner).snapshot().status());
                assertEquals("DEADLINE_EXCEEDED", second.snapshot().errorCode());
                assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id=? AND event_type='DONE'", Integer.class, second.id.toString()));
            } finally { lock.rollback(); }
            assertTrue(firstEnded.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("DEADLINE_EXCEEDED", repository.load(first.id, owner).snapshot().errorCode());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id=? AND event_type='DONE'", Integer.class, first.id.toString()));
        }
    }
    @Test void pendingFailureAdoptsSupersededTerminalWithoutOverwritingNextTurn() {
        RunState old = create("first"); old.start();
        String trigger = rejectDone(old);
        try { assertThrows(AgentException.class, () -> old.fail("MODEL_ERROR")); }
        finally { db.execute("DROP TRIGGER " + trigger); }
        RunState next = repository.create("next", owner, old.sessionId, UUID.randomUUID().toString(), null).run();
        repository.refresh(old); old.reconcileTerminal(old.terminalReconciliationAt());
        assertEquals(Status.CANCELLED, old.snapshot().status());
        assertEquals(StopReason.SUPERSEDED, old.snapshot().stopReason()); assertFalse(old.terminalPending());
        assertEquals(Status.QUEUED, repository.load(next.id, owner).snapshot().status());
        assertEquals(next.id.toString(), db.queryForObject("SELECT current_run_id FROM agent_session WHERE tenant_id=? AND session_id=?", String.class, owner.tenantId(), old.sessionId.toString()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id=? AND event_type='DONE'", Integer.class, old.id.toString()));
    }
    @Test void pendingFailureCanObserveCommittedCancellationAfterSessionDeletion() {
        RunState run = create("delete pending"); run.start();
        String trigger = rejectDone(run);
        try { assertThrows(AgentException.class, () -> run.fail("MODEL_ERROR")); }
        finally { db.execute("DROP TRIGGER " + trigger); }
        repository.deleteSession(run.sessionId, owner);
        repository.refresh(run);
        assertEquals(Status.CANCELLED, run.snapshot().status()); assertFalse(run.terminalPending());
        assertEquals("SESSION_NOT_FOUND", assertThrows(AgentException.class, () -> repository.load(run.id, owner)).code());
    }
    @Test void purgedPendingRunIsRetiredInsteadOfRetryingForever() throws Exception {
        var service = new RunService(mock(RequestOrchestrator.class), mock(RuntimeHarness.class), repository);
        try {
            RunState run = service.create("purged pending", owner); sessions.add(run.sessionId); run.start();
            String trigger = rejectDone(run);
            try { assertThrows(AgentException.class, () -> run.fail("MODEL_ERROR")); }
            finally { db.execute("DROP TRIGGER " + trigger); }
            repository.deleteSession(run.sessionId, owner); repository.garbageCollect();
            maintainUntil(service, () -> service.pendingTerminals() == 0);
            assertTrue(run.cancellation().cancelled());
            assertEquals(Instant.MAX, run.terminalReconciliationAt());
            assertEquals("RUN_NOT_FOUND", assertThrows(AgentException.class, () -> repository.load(run.id, owner)).code());
        } finally { service.shutdown(); }
    }
    @Test void pageContextAndOriginalWordsSurviveReloadAndIdempotency() {
        String key = "context-" + UUID.randomUUID();
        var page = new RequestContext("1001");
        RunState run = repository.create("昨天有多少条弹幕？", owner, null, key, null, page).run();
        sessions.add(run.sessionId);
        assertEquals("昨天有多少条弹幕？", run.message);
        assertEquals(run.message,run.query());assertEquals("1001",run.pageContext().videoId());
        assertEquals("1001", db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.context.videoId')) FROM agent_run WHERE run_id=?", String.class, run.id.toString()));
        faq(run);
        assertFalse(repository.create("昨天有多少条弹幕？", owner, null, key, null, page).fresh());
        assertThrows(AgentException.class, () -> repository.create("昨天有多少条弹幕？", owner, null, key, null, new RequestContext("1002")));
        repository.close(); repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16);
        RunState restored = repository.load(run.id, owner);
        assertEquals(run.message, restored.message);
        assertEquals(run.query(), restored.query());
        assertEquals("1001",restored.pageContext().videoId());
    }
    @Test void evidenceArtifactAndEventReplaySurviveRepositoryReconstruction() throws Exception {
        RunState run = create("metric"); run.start();
        var data = new MetricData(1001L, run.businessDate.minusDays(1), "bullet_count", 128, "v1", "integration-fixture", Instant.now(), "Asia/Shanghai", true, true);
        var evidence = new Evidence("ev_" + UUID.randomUUID(), run.id, owner, data); run.execution().evidence().put(evidence);
        run.succeed(new Answer("128", List.of(evidence.id())));
        assertEquals(1, repository.artifacts(run.id, owner).size());
        String artifact = repository.artifacts(run.id, owner).get(0).get("artifactId").toString();
        assertEquals(128, mapper.readTree(repository.artifact(run.id, artifact, owner)).get("value").asInt());
        repository.close(); repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16);
        RunState restored = repository.load(run.id, owner);
        assertEquals(128, restored.execution().evidence().get(evidence.id()).data().value());
        assertEquals(1, restored.events().after(0).events().stream().filter(e -> e.type().equals("DONE")).count());
        assertEquals(Status.SUCCEEDED, restored.snapshot().status());
    }
    @Test void aNarrowedScopeCannotReuseTheBroaderCachedHistory() {
        RunState original = create("private historical fixture"); faq(original); repository.deliverOutbox();
        var narrow = new AccessScope(owner.tenantId(), owner.userId(), Set.of(1002L));
        RunState next = repository.create("new", narrow, original.sessionId, null, null).run();
        var messages=repository.memory(next).recent();assertEquals(2,messages.size());
        assertEquals("user",messages.get(0).role());assertEquals(original.message,messages.get(0).text());
        assertEquals("assistant",messages.get(1).role());assertEquals("[先前视频结果当前无权限，已隐藏]",messages.get(1).text());
        assertTrue(messages.stream().noneMatch(message->message.text().equals("approved")));
        assertEquals(messages,repository.memory(next).recent());
        assertEquals("FORBIDDEN", assertThrows(AgentException.class, () -> repository.load(original.id, narrow)).code());
        next.cancel();
    }
    @Test void secondWriterIsRejectedAndDeletingSessionRemovesPrivateObjects() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16));
        RunState run = create("delete"); run.start();
        String id = "ev_" + UUID.randomUUID();
        run.execution().evidence().put(new Evidence(id, run.id, owner, new MetricData(1001L, run.businessDate.minusDays(1), "bullet_count", 1, "v1", "fixture", Instant.now(), "Asia/Shanghai", true, true)));
        run.succeed(new Answer("1", List.of(id)));
        try (var files = java.nio.file.Files.list(directory)) { assertEquals(1, files.count()); }
        repository.deleteSession(run.sessionId, owner);
        assertEquals("SESSION_NOT_FOUND", assertThrows(AgentException.class, () -> repository.load(run.id, owner)).code());
        repository.garbageCollect(); assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE run_id=?", Integer.class, run.id.toString()));
        try (var files = java.nio.file.Files.list(directory)) { assertEquals(0, files.count()); }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM agent_audit_event WHERE tenant_id=? AND session_id=? AND action='SESSION_DELETE_REQUESTED'", Integer.class, owner.tenantId(), run.sessionId.toString()));
    }
    @Test void liveEvidenceAndHtmlReportSurviveReloadAndAreDeletedWithSession() throws Exception {
        RunState run=create("live report"); run.start();
        var q=new com.tianmu.agent.core.DanmakuTypes.Query(1001,run.businessDate.minusDays(1).atStartOfDay(),run.businessDate.atStartOfDay());
        var bins=new java.util.ArrayList<com.tianmu.agent.core.DanmakuTypes.Bucket>();
        for(int i=0;i<12;i++) bins.add(new com.tianmu.agent.core.DanmakuTypes.Bucket(i,0));
        var data=new com.tianmu.agent.core.DanmakuTypes.Dataset(q,0,0,bins,List.of(),Instant.now(),"fixture",true);
        String id="ev_"+UUID.randomUUID();
        run.execution().evidence().put(new Evidence(id,run.id,owner,null,data));
        assertEquals(data,repository.load(run.id,owner).execution().evidence().get(id).analysis());
        byte[] html="<!doctype html><html>report</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var ref=repository.saveReport(run.id,owner,id,html);
        run.emit("ARTIFACT_READY",Map.of("artifact_id",ref.artifactId(),"url",ref.url(),"media_type","text/html"));
        run.succeed(new Answer("report",List.of(id)));
        repository.close();
        repository=new MysqlRunRepository(source,mapper,cache,new FileObjectStore(directory),Duration.ofSeconds(30),16);
        assertArrayEquals(html,repository.report(run.id,owner,ref.artifactId()));
        assertEquals(1,repository.load(run.id,owner).events().after(0).events().stream().filter(e->e.type().equals("ARTIFACT_READY")).count());
        assertThrows(AgentException.class,()->repository.report(run.id,new AccessScope(owner.tenantId(),"bob",owner.videoIds()),ref.artifactId()));
        repository.deleteSession(run.sessionId,owner); repository.garbageCollect();
        try(var files=java.nio.file.Files.list(directory)) { assertEquals(0,files.count()); }
    }
    @Test void interruptedExecutionBecomesRunnerLostAndTheOldWriterCannotPublish() {
        RunState run = create("interrupted"); run.start(); repository.close();
        repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16);
        Snapshot recovered = repository.load(run.id, owner).snapshot();
        assertEquals(Status.FAILED, recovered.status()); assertEquals("RUNNER_LOST", recovered.errorCode());
        assertThrows(AgentException.class, () -> run.succeed(new Answer("late", List.of())));
        assertNull(repository.load(run.id, owner).snapshot().answer());
        assertEquals(1, repository.load(run.id, owner).events().after(0).events().stream().filter(e -> e.type().equals("DONE")).count());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void restartAcrossResultBindingRetainsTheDocumentedHandoffBoundary(boolean bindBeforeCrash) {
        RunState run = create("governance recovery handoff");
        run.start();
        run.preparationCallStarted();
        var operation = new PendingOperation(UUID.randomUUID(), "feedback-analysis", run.deadline());
        run.execution().observe(new ToolCall("analyze_feedback", Map.of(), "handoff-provider-call", "handoff-group", "我先查一下。", "先核对范围再分析。"),
                new ToolResult("analyze_feedback", Map.of("status", "PENDING"), null, null, operation));
        run.waitFor(operation);
        if (bindBeforeCrash) {
            assertTrue(run.completeOperation(operation, new ToolResult("analyze_feedback", Map.of("status", "READY"), "ev_handoff")));
            assertNull(db.queryForObject("SELECT continuation_json FROM agent_run WHERE run_id=?", String.class, run.id.toString()));
        }

        repository.close(); // No resume runnable has been submitted at either simulated crash point.
        repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16);
        var recovered = repository.load(run.id, owner);
        assertEquals(1, recovered.snapshot().preparationCalls());
        if (bindBeforeCrash) {
            assertEquals(Status.FAILED, recovered.snapshot().status());
            assertEquals("RUNNER_LOST", recovered.snapshot().errorCode());
            assertNull(recovered.continuation());
            assertEquals(1, recovered.events().after(0).events().stream().filter(e -> e.type().equals("DONE")).count());
        } else {
            assertEquals(Status.WAITING_OPERATION, recovered.snapshot().status());
            assertEquals(operation, recovered.continuation().operation());
            assertEquals("handoff-provider-call", recovered.execution().exchanges().get(0).call().providerCallId());
            assertEquals("先核对范围再分析。", recovered.execution().exchanges().get(0).call().reasoningContent());
            assertTrue(recovered.completeOperation(operation, new ToolResult("analyze_feedback", Map.of("status", "READY"), "ev_handoff")));
            assertFalse(recovered.completeOperation(operation, new ToolResult("analyze_feedback", Map.of("status", "READY"), "ev_handoff")));
            recovered.cancel();
        }
    }

    @Test void qaAdmissionIsDurableAndSessionReplayDoesNotInflateCounts()throws Exception{
        var policy=new com.tianmu.agent.faq.QaPolicy(true,2,1,30,7,256);
        var qa=new com.tianmu.agent.faq.QaCatalog(new MysqlQaStore(source,mapper),policy);
        String question="如何设计选题主题",answer="先确定面向的观众，再整理可以持续观察和讨论的选题方向。";UUID session=UUID.randomUUID();
        String ownerText=owner.tenantId().length()+":"+owner.tenantId()+owner.userId().length()+":"+owner.userId();
        String ownerKey=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ownerText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try{
            qa.observe(owner,session,UUID.randomUUID(),question,answer,Instant.now());qa.observe(owner,session,UUID.randomUUID(),question,answer,Instant.now());
            assertEquals(1,qa.list(owner).get(0).sessions());
            qa.observe(owner,UUID.randomUUID(),UUID.randomUUID(),question,answer,Instant.now());
            var restarted=new com.tianmu.agent.faq.QaCatalog(new MysqlQaStore(source,mapper),policy);
            assertEquals(answer,restarted.find(new Input(question,LocalDate.now(),owner)).orElseThrow().text());
        }finally{db.update("DELETE FROM agent_qa_catalog WHERE owner_key=?",ownerKey);}
    }

}
