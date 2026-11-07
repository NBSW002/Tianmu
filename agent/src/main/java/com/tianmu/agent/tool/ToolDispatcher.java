package com.tianmu.agent.tool;

import com.tianmu.agent.core.VideoContent;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import org.springframework.stereotype.Component;
import java.time.DateTimeException;
import java.util.*;
import com.tianmu.agent.harness.hook.HookPoint;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.*;
import java.util.function.Function;
import com.tianmu.agent.harness.trace.TraceDetails;

/** Fixed admission boundary; tool implementations register independently of this dispatcher. */
@Component
public class ToolDispatcher {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.MemoryToolService memories;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.MemoryLedger memoryLedger;
    public sealed interface DispatchOutcome permits Completed, ValidationRejected {}
    public record Completed(ToolResult result) implements DispatchOutcome {}
    public record ValidationRejected(ToolArguments.Validation validation) implements DispatchOutcome {
        public ValidationRejected { if(validation.valid())throw new IllegalArgumentException("Expected invalid arguments"); }
    }
    private final SkillCatalog skills;
    private final com.tianmu.agent.application.BusinessEvidencePolicy business = new com.tianmu.agent.application.BusinessEvidencePolicy();
    private final RuntimeHarness harness;
    private final ToolRegistry registry;
    private final com.tianmu.agent.application.ReportArtifacts artifacts;
    private final com.tianmu.agent.runtime.store.ReportStore reports;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.runtime.store.RunRepository repository;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.access.IdentityPort identity;
    private final com.tianmu.agent.runtime.store.ConversationStore conversations;
    private com.tianmu.agent.feedback.FeedbackAnalysisService feedback;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.feedback.FeedbackPreparationService preparation;
    @org.springframework.beans.factory.annotation.Autowired
    void feedbackService(com.tianmu.agent.feedback.FeedbackAnalysisService feedback){this.feedback=feedback;}
    @org.springframework.beans.factory.annotation.Autowired
    public ToolDispatcher(SkillCatalog skills,RuntimeHarness harness,ToolRegistry registry,com.tianmu.agent.runtime.store.ReportStore reports,
                          com.tianmu.agent.runtime.store.ConversationStore conversations) {
        this.skills = skills; this.harness = harness; this.registry = registry;
        this.artifacts = new com.tianmu.agent.application.ReportArtifacts(reports);this.reports=reports;
        if(reports instanceof com.tianmu.agent.runtime.store.RunRepository value)this.repository=value;
        this.conversations = conversations;
    }
    public ToolDispatcher(SkillCatalog skills, RuntimeHarness harness, ToolRegistry registry, com.tianmu.agent.runtime.store.ReportStore reports) {
        this(skills,harness,registry,reports,null);
    }
    public ToolDispatcher(SkillCatalog skills, RuntimeHarness harness, ToolRegistry registry) { this(skills, harness, registry, null); }
    public List<ToolDefinition> definitions(Set<String> activeSkills) { return registry.definitions(activeSkills); }
    public List<ToolDefinition> definitions(RunState run) {
        return registry.definitions(run.execution().activeSkills(), snapshotTools(run)).stream()
                .filter(d -> !d.name().equals("analyze_feedback") || preparation==null || run.task()==null
                        || !run.task().specification().reading().reportRequired()
                        || run.execution().exchanges().stream().anyMatch(e->e.call().name().equals("analyze_feedback")))
                .filter(t -> permitted(t.name(), run)).toList();
    }
    private boolean permitted(String name, RunState run) {
        if(com.tianmu.agent.memory.MemoryToolService.NAMES.contains(name) && (memories==null || !memories.available(name,run)))return false;
        return com.tianmu.agent.access.AssistantToolAccess.permits(name, run.owner) && com.tianmu.agent.application.AnalysisExecutionPolicy.permits(name, run.task() == null ? null : run.task().specification());
    }
    private static Evidence bindFeedback(RunState run,com.tianmu.agent.core.FeedbackAnalysis.Asset asset){return com.tianmu.agent.application.FeedbackEvidenceBinding.bind(run,asset);}
    private Set<String> snapshotTools(RunState run) { return run.task() == null ? registry.names() : run.task().allowedTools(); }
    public ToolCall clarificationCall(String question){return new ToolCall(registry.roleName(AgentTool.Role.CLARIFICATION),Map.of("question",question));}
    public boolean sharedSuspension(List<ToolCall> calls,RunState run){
        if(calls.isEmpty())return false;
        String group=registry.admit(calls.get(0).name(),run.execution().activeSkills(),snapshotTools(run)).suspensionGroup();
        return group!=null && calls.stream().allMatch(c->group.equals(registry.admit(c.name(),run.execution().activeSkills(),snapshotTools(run)).suspensionGroup()));
    }
    public void checkBatch(List<ToolCall> calls,RunState run) {
        if(calls.size()<2 || calls.size()>4)throw new AgentException("INVALID_DECISION","Native read batch must contain two to four calls");
        Set<ToolCall> unique=new HashSet<>();
        if(calls.stream().anyMatch(c->registry.get(c.name()).suspensionGroup()!=null)
                && (!sharedSuspension(calls,run) || calls.stream().map(ToolCall::name).distinct().count()!=calls.size()))
            throw new AgentException("INVALID_DECISION","Source preparation batches contain one call per selected source only");
        for(var call:calls) {
            if (!permitted(call.name(), run)) throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Tool is outside the frozen source selection");
            var tool=registry.admit(call.name(),run.execution().activeSkills(),snapshotTools(run));
            if(!tool.batchable() || !unique.add(new ToolCall(call.name(),call.arguments())))
                throw new AgentException("INVALID_DECISION","Only distinct independent reads can share a native tool batch");
            ToolArguments.validate(call,registry.definition(call.name()));
        }
    }
    public ToolResult execute(ToolCall call, RunState run) {
        var outcome=dispatch(call,run);
        if(outcome instanceof Completed completed)return completed.result();
        throw new AgentException("INVALID_ARGUMENTS","Tool arguments do not match the declared schema");
    }
    public boolean parallelSafe(ToolCall call,RunState run){
        var tool=registry.admit(call.name(),run.execution().activeSkills(),snapshotTools(run));
        if(!tool.parallelSafe())return false;
        if(call.name().equals("read_result")){
            var e=run.execution().evidence().get(Objects.toString(call.arguments().get("result_id"),""));
            return e!=null && e.lookup()==null && e.feedback()==null;
        }
        return true;
    }
    public Branch branch(ToolCall call,RunState run){
        if(!parallelSafe(call,run) || !permitted(call.name(),run))throw new AgentException("TOOL_CAPABILITY_DENIED","Tool is not an isolated read");
        return new Branch(call,run,registry.admit(call.name(),run.execution().activeSkills(),snapshotTools(run)));
    }
    public final class Branch {
        private final ToolCall call;private final RunState run;private final AgentTool tool;
        private final com.tianmu.agent.runtime.store.InMemoryEvidenceStore delta=new com.tianmu.agent.runtime.store.InMemoryEvidenceStore();
        private final Map<String,Evidence> evidence;
        private final com.tianmu.agent.runtime.EvidenceContext policy;
        private final ToolContext context;
        Branch(ToolCall call,RunState run,AgentTool tool){
            this.call=call;this.run=run;this.tool=tool;evidence=run.execution().evidence().snapshot();
            var owner=run.owner;var task=run.task();var date=run.businessDate;
            policy=new com.tianmu.agent.runtime.EvidenceContext(){
                public UUID runId(){return run.id;}public AccessScope scope(){return owner;}public LocalDate businessDate(){return date;}
                public com.tianmu.agent.core.TaskTypes.PreparedTask task(){return task;}
                public com.tianmu.agent.runtime.store.EvidenceStore evidenceStore(){return delta;}
                public void checkpoint(){run.checkpoint();}
            };
            context=new ToolContext(){
                public UUID runId(){return run.id;}public AccessScope scope(){return owner;}public LocalDate businessDate(){return date;}
                public void checkpoint(){run.checkpoint();}
                public List<Map<String,String>> listSkills(){return List.of();}
                public Skill activateSkill(String name){throw new AgentException("TOOL_CAPABILITY_DENIED","Parallel reads cannot activate skills");}
                public List<ToolDefinition> definitionsForSkill(String name){return List.of();}
                public Evidence evidence(String id){var e=evidence.get(id);if(e==null || !e.runId().equals(run.id) || !e.owner().equals(owner))throw new AgentException("RESULT_NOT_FOUND","Evidence outside snapshot");return e;}
                public void checkQuery(MetricQuery q){business.checkQuery(policy,q);}
                public Evidence registerEvidence(MetricData d){return business.registerEvidence(policy,d);}
                public void checkDanmakuQuery(com.tianmu.agent.core.DanmakuTypes.Query q){business.checkDanmakuQuery(policy,q);}
                public Evidence registerDanmakuEvidence(com.tianmu.agent.core.DanmakuTypes.Dataset d){return business.registerDanmakuEvidence(policy,d);}
                public void checkVideoQuery(long id){business.checkVideoQuery(policy,id);}
                public void checkVideoQuery(long id,Long a,Long b){business.checkVideoQuery(policy,id,a,b);}
                public Evidence registerVideoEvidence(VideoContent.Facts f){return business.registerVideoEvidence(policy,f);}
                public <T>T query(Function<CallControl,T> operation){return harness.toolCall(run,operation);}
                public <T>T videoQuery(Function<CallControl,T> operation){return harness.videoToolCall(run,operation);}
            };
        }
        public ToolResult execute(){
            run.checkpoint();run.verifyMemory();
            ToolArguments.validate(call,registry.definition(call.name()));
            var result=tool.execute(call,context);validate(result);return result;
        }
        public void validate(ToolResult result){
            if(result==null || !result.tool().equals(call.name()) || result.pending()!=null || result.terminalAnswer()!=null || result.terminalClarification()!=null || result.data().containsKey("input_validation"))
                throw new AgentException("INVALID_TOOL_RESULT","Parallel read cannot mutate delivery state");
            if(result.evidenceId()!=null && !delta.snapshot().containsKey(result.evidenceId()) && !evidence.containsKey(result.evidenceId()))throw new AgentException("INVALID_EVIDENCE","Unknown branch evidence");
        }
        public void commit(ToolResult result){
            run.checkpoint();run.verifyMemory();validate(result);
            for(var e:delta.snapshot().values()){
                if(e.data()!=null)business.checkQuery(run,new MetricQuery(e.data().videoId(),e.data().date(),e.data().metric()));
                if(e.videoContent()!=null)business.checkVideoQuery(run,e.videoContent().videoId(),e.videoContent().startMs(),e.videoContent().endMs());
                if(e.analysis()!=null)business.checkDanmakuQuery(run,e.analysis().query());
                run.execution().evidence().put(e);
            }
        }
    }
    /** Only this pre-handler branch can grant input correction; handler exceptions never become this outcome. */
    public DispatchOutcome dispatch(ToolCall call, RunState run) {
        run.checkpoint();
        if (!permitted(call.name(), run)) throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Tool is outside the frozen source selection");
        AgentTool tool = registry.admit(call.name(), run.execution().activeSkills(), snapshotTools(run));
        try {
            var span = io.opentelemetry.api.trace.Span.current();
            com.tianmu.agent.harness.trace.TraceAttributes.apply(span, Map.of("tool.call_id", span.getSpanContext().getSpanId()));
            TraceDetails.capture("input", call, "TOOL_ARGUMENTS");
            com.tianmu.agent.harness.trace.TraceAttributes.apply(span,Map.of("tool.handler_executed",false));
            var validation=ToolArguments.inspect(call,registry.definition(call.name()));
            run.checkpoint();
            if(!validation.valid())return new ValidationRejected(validation);
            var context=new HostContext(run,tool);
            com.tianmu.agent.harness.trace.TraceAttributes.apply(span,Map.of("tool.handler_executed",true));
            ToolResult result = tool.execute(call, context);
            run.checkpoint();
            if (result == null || !call.name().equals(result.tool()) || result.data().containsKey("input_validation")
                    || result.terminalClarification()!=null && !Set.of("ask_clarification","remember_memory","forget_memory").contains(call.name()))
                throw new AgentException("INVALID_TOOL_RESULT", "Tool result does not match the registered handler or uses host-reserved feedback");
            TraceDetails.capture("output", result, "TOOL_RESULT");
            return new Completed(result);
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
            throw new AgentException("INVALID_ARGUMENTS", "Tool arguments are invalid");
        }
    }
    private final class HostContext implements ToolContext {
        private final RunState run; private final AgentTool tool;
        private final com.tianmu.agent.application.HistoryQueryService history;
        HostContext(RunState run, AgentTool tool) {
            this.run = run; this.tool = tool;
            this.history = new com.tianmu.agent.application.HistoryQueryService(conversations, run.sessionId, run.owner, run.turnSeq,messages->memoryLedger!=null?memoryLedger.inherit(run,messages):memories==null?messages:memories.inherit(run,messages));
        }
        public UUID runId() { return run.id; }
        public AccessScope scope() { return run.owner; }
        public LocalDate businessDate() { return run.businessDate; }
        public void checkpoint() { run.checkpoint(); }
        public ToolResult memory(ToolCall call){if(memories==null)throw new AgentException("MEMORY_DISABLED","Personal memory is disabled");return memories.execute(run,call);}
        public Map<String,Object> history(long before,int limit,String query) {
            run.checkpoint();
            return history.read(before,limit,query);
        }
        public com.tianmu.agent.core.AnalysisContract analysisContract(){return run.task()==null?null:run.task().specification().analysis();}
        public List<Map<String, String>> listSkills() { return skills.list().stream().filter(item -> skills.read(item.get("name")).tools().stream().anyMatch(name -> permitted(name,run))).toList(); }
        public Skill activateSkill(String name) {
            if (!tool.canActivateSkills()) throw new AgentException("TOOL_CAPABILITY_DENIED", "Tool cannot activate skills");
            checkSkillRevision(name);
            Skill skill = skills.read(name);
            if(skill.tools().stream().noneMatch(toolName -> permitted(toolName,run)))throw new AgentException("SKILL_REQUIRED","Skill is unavailable for this request");
            return harness.stage(run, "agent.skill", HookPoint.PRE_SKILL, HookPoint.POST_SKILL,
                    Map.of("skill.name", name, "skill.version", skill.version()), () -> {
                        TraceDetails.input(Map.of("name", name, "version", skill.version()));
                        run.execution().activateSkill(name); return skill;
                    });
        }
        private void checkSkillRevision(String name) {
            Skill skill = skills.read(name);
            if (run.task() == null) return;
            var versions = run.task().resourceVersions();
            String version = versions.get("skill:" + name), fingerprint = versions.get("skill-content:" + name);
            if (version != null && !version.equals(skill.version())
                    || fingerprint != null && !fingerprint.equals(skills.fingerprint(name)))
                throw new AgentException("RESOURCE_VERSION_MISMATCH", "Skill differs from the prepared resource snapshot");
        }
        public List<Map<String, Object>> skillResources(String name) {
            checkSkillRevision(name);
            return skills.resources(name);
        }
        public Map<String, Object> skillResource(String name, String resource, int offset, int limit) {
            if (!tool.canActivateSkills() || !run.execution().activeSkills().contains(name))
                throw new AgentException("SKILL_REQUIRED", "Read the skill instructions before its references");
            checkSkillRevision(name);
            return skills.resource(name, resource, offset, limit);
        }
        public List<ToolDefinition> definitionsForSkill(String name) {
            Skill skill = skills.read(name);
            return definitions(run).stream().filter(d -> skill.tools().contains(d.name())).toList();
        }
        public Evidence evidence(String id) {
            Evidence value = run.execution().evidence().get(id);
            if (value == null || !value.owner().equals(run.owner) || !value.runId().equals(run.id))
                throw new AgentException("RESULT_NOT_FOUND", "Result is not accessible in this run");
            return value;
        }
        public Evidence registerLookup(com.tianmu.agent.core.LookupFacts facts){
            if(!tool.name().equals(facts.operation()) && !(tool.name().equals("read_result") && facts.operation().equals("read_report")))throw new AgentException("TOOL_CAPABILITY_DENIED","Resource operation mismatch");
            var old=run.execution().evidence().snapshot().values().stream().filter(e->facts.equals(e.lookup())).findFirst();if(old.isPresent())return old.get();
            var value=new Evidence("ev_"+UUID.randomUUID(),run.id,run.owner,null,null,null,null,null,null,facts);run.execution().evidence().put(value);return value;
        }
        private com.tianmu.agent.application.HistoricalReportReader reader(){return new com.tianmu.agent.application.HistoricalReportReader(repository,reports,feedback);}
        private com.tianmu.agent.application.HistoricalReportReader.Source reportSource(UUID id,String artifact){
            var caller=identity==null?run.owner:identity.forHistoricalRun(run.owner,id);
            return reader().source(id,artifact,caller,run.task()==null?null:run.task().specification());
        }
        public ToolResult readReport(UUID sourceRun,String artifact,String section,int offset,int limit){
            if(!Set.of("summary","topics","observations").contains(section))throw new AgentException("INVALID_ARGUMENTS","Historical reports support summary, topics, observations, or original_text with original references");
            run.checkpoint();var source=reportSource(sourceRun,artifact);var data=reader().read(source,artifact,section,offset,limit);
            var existing=run.execution().evidence().snapshot().values().stream().filter(e->e.lookup()!=null && e.lookup().operation().equals("read_report")
                    && sourceRun.toString().equals(e.lookup().data().get("source_run_id")) && artifact.equals(e.lookup().data().get("artifact_id"))).findFirst();
            var value=existing.orElseGet(()->registerLookup(new com.tianmu.agent.core.LookupFacts("read_report",Map.of("source_run_id",sourceRun.toString(),"artifact_id",artifact,"historical",true,"generated_at",source.report().createdAt()),source.videoIds())));
            return new ToolResult(tool.name(),com.tianmu.agent.core.LookupFacts.normalize(data),value.id());
        }
        public Map<String,Object> readReportOriginal(Evidence evidence,String topic,String original,int offset,int limit){
            if(evidence.lookup()==null || !evidence.lookup().operation().equals("read_report"))throw new AgentException("INVALID_EVIDENCE","Historical report reference required");
            var source=reportSource(UUID.fromString(evidence.lookup().data().get("source_run_id").toString()),evidence.lookup().data().get("artifact_id").toString());
            if(source.evidence().feedback()==null)throw new AgentException("RESULT_NOT_FOUND","Report has no retained feedback snapshot");
            return feedback.originalText(feedback.historicalAsset(source.evidence().feedback().jobId(),source.authorized()),topic,original,offset,limit);
        }
        public Map<String,Object> searchFeedback(Evidence evidence,String query,int offset,int limit){
            run.checkpoint();com.tianmu.agent.core.FeedbackAnalysis.Asset asset;
            if(evidence.feedback()!=null){checkFeedback(evidence.feedback().request());asset=feedback.asset(evidence.feedback().jobId(),run.owner,evidence.feedback().request());}
            else if(evidence.lookup()!=null && evidence.lookup().operation().equals("read_report")){
                var source=reportSource(UUID.fromString(evidence.lookup().data().get("source_run_id").toString()),evidence.lookup().data().get("artifact_id").toString());
                if(source.evidence().feedback()==null)throw new AgentException("INVALID_EVIDENCE","This historical report has no feedback snapshot");
                asset=feedback.historicalAsset(source.evidence().feedback().jobId(),source.authorized());
            }else throw new AgentException("INVALID_EVIDENCE","Search requires completed feedback or a historical feedback report");
            return feedback.search(asset,query,offset,limit);
        }
        public void checkFeedback(com.tianmu.agent.core.FeedbackAnalysis.Request request){
            run.checkpoint();com.tianmu.agent.application.FeedbackTaskPolicy.require(run.task()==null?null:run.task().specification(),request);
            if(!run.owner.videoIds().contains(request.query().videoId()))throw new AgentException("FORBIDDEN","Feedback video outside scope");
        }
        public ToolResult analyzeFeedback(String videoEvidenceId,String analysisId){
            if(feedback==null || !tool.name().equals("analyze_feedback"))throw new AgentException("FEEDBACK_SERVICE_UNAVAILABLE","Background analysis unavailable");
            var request=com.tianmu.agent.application.FeedbackTaskPolicy.request(run.task()==null?null:run.task().specification());checkFeedback(request);
            VideoContent.Facts video=null;
            if(videoEvidenceId!=null){var ev=evidence(videoEvidenceId);video=ev.videoContent();if(video==null)throw new AgentException("INVALID_EVIDENCE","Expected current video evidence");business.checkVideoQuery(run,video.videoId(),video.startMs(),video.endMs());}
            if(analysisId!=null){
                var asset=feedback.asset(UUID.fromString(analysisId),run.owner,request);
                if(request.grounding()==com.tianmu.agent.core.FeedbackAnalysis.Grounding.VIDEO_GROUNDED && (video==null || !video.sourceVersion().equals(asset.video().sourceVersion()) || video.startMs()!=asset.video().startMs() || video.endMs()!=asset.video().endMs()))
                    throw new AgentException("INVALID_EVIDENCE","Reused analysis must match the current video version and evidence interval");
                var ev=bindFeedback(run,asset);
                return new ToolResult(tool.name(),Map.of("status","READY","evidence_id",ev.id(),"summary",feedback.read(asset,"summary",0,1)),ev.id());
            }
            var job=feedback.submit(run.id,run.owner,request,video,run.deadline());
            if(job.phase()==com.tianmu.agent.core.FeedbackAnalysis.Phase.READY){var asset=feedback.asset(job.id(),run.owner,request);var ev=bindFeedback(run,asset);return new ToolResult(tool.name(),Map.of("status","READY","evidence_id",ev.id(),"summary",feedback.read(asset,"summary",0,1)),ev.id());}
            if(!com.tianmu.agent.feedback.MemoryFeedbackJobStore.active(job.phase()))throw new AgentException("ANALYSIS_NOT_READY","Analysis cannot be started in this state");
            return new ToolResult(tool.name(),Map.of("status","PENDING","operation_id",job.id().toString()),null,null,new com.tianmu.agent.core.PendingOperation(job.id(),"feedback-analysis",job.deadline()));
        }
        public ToolResult prepareFeedback(com.tianmu.agent.core.FeedbackAnalysis.Source source,String videoEvidenceId){
            if(preparation==null || !tool.name().equals(com.tianmu.agent.application.FeedbackSourceEvidence.tool(source)))throw new AgentException("TOOL_CAPABILITY_DENIED","Source preparation unavailable");
            var request=com.tianmu.agent.application.FeedbackTaskPolicy.pipelineRequest(run.task().specification());checkFeedback(request);
            VideoContent.Facts video=null;
            if(request.grounding()==com.tianmu.agent.core.FeedbackAnalysis.Grounding.VIDEO_GROUNDED){
                var candidates=run.execution().evidence().snapshot().values().stream().filter(e->e.videoContent()!=null && (videoEvidenceId==null || e.id().equals(videoEvidenceId)))
                        .filter(e->e.videoContent().videoId()==request.query().videoId()).toList();
                if(candidates.size()!=1)throw new AgentException("INVALID_EVIDENCE","Read the selected video evidence before preparing sources");
                video=candidates.get(0).videoContent();business.checkVideoQuery(run,video.videoId(),video.startMs(),video.endMs());
            }else if(videoEvidenceId!=null)throw new AgentException("TASK_CONSTRAINT_VIOLATION","Video was not selected");
            var job=preparation.request(run.id,run.owner,request,video,run.deadline(),source);
            if(preparation.ready(job.id(),source))return com.tianmu.agent.application.FeedbackSourceEvidence.bind(run,job,preparation.result(job.id(),run.owner,source,run.callControl()));
            if(!com.tianmu.agent.feedback.MemoryFeedbackJobStore.active(job.phase()))throw new AgentException("ANALYSIS_NOT_READY","Source preparation cannot proceed");
            return new ToolResult(tool.name(),Map.of("status","PENDING","operation_id",job.id().toString()),null,null,new com.tianmu.agent.core.PendingOperation(job.id(),"feedback-sources",job.deadline()));
        }
        public ToolResult createPreparedReport(String comment,String danmaku){
            if(preparation==null || !tool.name().equals("create_feedback_report") || analysisContract()==null || !analysisContract().reportRequired())throw new AgentException("TOOL_CAPABILITY_DENIED","Report was not requested");
            var request=com.tianmu.agent.application.FeedbackTaskPolicy.pipelineRequest(run.task().specification());UUID jobId=null;
            for(var source:com.tianmu.agent.core.FeedbackAnalysis.Source.values()){
                String id=source==com.tianmu.agent.core.FeedbackAnalysis.Source.COMMENT?comment:danmaku;
                if(request.sources().contains(source)!=(id!=null))throw new AgentException("INVALID_EVIDENCE","Provide exactly the selected source evidence");
                if(id==null)continue;var ev=evidence(id);
                if(ev.lookup()==null || !ev.lookup().operation().equals(com.tianmu.agent.application.FeedbackSourceEvidence.tool(source)))throw new AgentException("INVALID_EVIDENCE","Expected source evidence from this run");
                UUID candidate=UUID.fromString(ev.lookup().data().get("job_id").toString());
                if(jobId!=null && !jobId.equals(candidate))throw new AgentException("INVALID_EVIDENCE","Sources belong to different jobs");jobId=candidate;
            }
            var job=feedback.owned(jobId,run.owner);checkFeedback(job.request());
            if(!job.runId().equals(run.id))throw new AgentException("INVALID_EVIDENCE","Sources belong to another run");
            job=preparation.startReport(jobId,run.owner,run.callControl());
            if(job.phase()==com.tianmu.agent.core.FeedbackAnalysis.Phase.READY){
                var asset=feedback.asset(job.id(),run.owner,request);var ev=bindFeedback(run,asset);var report=saveReport(ev.id(),feedback.report(asset));
                return new ToolResult(tool.name(),Map.of("status","READY","artifact_id",report.artifactId(),"url",report.url()),ev.id(),
                        new Answer((asset.model().equals("demo-scripted-feedback")?"【模拟数据】":"")+"反馈报告已生成并保存。\n[查看 HTML 报告]("+report.url()+")（需登录鉴权）",List.of(ev.id())));
            }
            if(!com.tianmu.agent.feedback.MemoryFeedbackJobStore.active(job.phase()))throw new AgentException("ANALYSIS_NOT_READY","Report cannot proceed");
            return new ToolResult(tool.name(),Map.of("status","PENDING","operation_id",job.id().toString()),null,null,new com.tianmu.agent.core.PendingOperation(job.id(),"feedback-report",job.deadline()));
        }
        public Map<String,Object> readFeedback(Evidence evidence,String section,int offset,int limit){
            checkFeedback(evidence.feedback().request());var asset=feedback.asset(evidence.feedback().jobId(),run.owner,evidence.feedback().request());return feedback.read(asset,section,offset,limit);
        }
        public Map<String,Object> readFeedback(Evidence evidence,String section,String topic,int offset,int limit){
            checkFeedback(evidence.feedback().request());var asset=feedback.asset(evidence.feedback().jobId(),run.owner,evidence.feedback().request());return feedback.read(asset,section,topic,offset,limit);
        }
        public void checkFeedbackTopic(Evidence evidence,String topic){
            checkFeedback(evidence.feedback().request());var asset=feedback.asset(evidence.feedback().jobId(),run.owner,evidence.feedback().request());
            feedback.topic(asset,topic);
        }
        public Map<String,Object> readFeedbackOriginal(Evidence evidence,String topic,String original,int offset,int limit){
            checkFeedback(evidence.feedback().request());var asset=feedback.asset(evidence.feedback().jobId(),run.owner,evidence.feedback().request());return feedback.originalText(asset,topic,original,offset,limit);
        }
        public void checkQuery(MetricQuery query) { business.checkQuery(run, query); }
        public Evidence registerEvidence(MetricData data) { return business.registerEvidence(run, data); }
        public <T> T query(Function<CallControl, T> operation) { return harness.toolCall(run, operation); }
        public <T> T videoQuery(Function<CallControl, T> operation) { return harness.videoToolCall(run, operation); }
        public void checkVideoQuery(long videoId) { business.checkVideoQuery(run, videoId); }
        public void checkCreatorDraft(long videoId, String kind) {
            run.checkpoint();
            if (!run.owner.videoIds().contains(videoId)) throw new AgentException("FORBIDDEN", "Creator draft requires ownership of this video");
            if (run.task() == null || !(run.task().specification().typed() instanceof com.tianmu.agent.core.TaskConditions.Interaction expected)
                    || expected.video().videoId() != videoId || !expected.kind().equals(kind))
                throw new com.tianmu.agent.core.AgentException("TASK_CONSTRAINT_VIOLATION", "草稿对象或类型与用户请求不一致。");
        }
        public void checkVideoQuery(long videoId, Long startMs, Long endMs) { business.checkVideoQuery(run, videoId, startMs, endMs); }
        public Evidence registerVideoEvidence(com.tianmu.agent.core.VideoContent.Facts facts) { return business.registerVideoEvidence(run, facts); }
        public void checkDanmakuQuery(com.tianmu.agent.core.DanmakuTypes.Query query) { business.checkDanmakuQuery(run, query); }
        public Evidence registerDanmakuEvidence(com.tianmu.agent.core.DanmakuTypes.Dataset data) { return business.registerDanmakuEvidence(run, data); }
        public com.tianmu.agent.core.DanmakuTypes.ReportRef saveReport(String evidenceId, byte[] html) { return artifacts.save(run, evidenceId, html); }
    }
}
