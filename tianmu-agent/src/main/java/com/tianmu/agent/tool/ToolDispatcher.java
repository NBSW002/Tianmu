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
    private final com.tianmu.agent.runtime.store.ConversationStore conversations;
    private com.tianmu.agent.feedback.FeedbackAnalysisService feedback;
    private boolean feedbackEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    void feedbackService(com.tianmu.agent.feedback.FeedbackAnalysisService feedback,com.tianmu.agent.feedback.FeedbackPolicy policy){this.feedback=feedback;feedbackEnabled=policy.enabled();}
    @org.springframework.beans.factory.annotation.Autowired
    public ToolDispatcher(SkillCatalog skills,RuntimeHarness harness,ToolRegistry registry,com.tianmu.agent.runtime.store.ReportStore reports,
                          com.tianmu.agent.runtime.store.ConversationStore conversations) {
        this.skills = skills; this.harness = harness; this.registry = registry;
        this.artifacts = new com.tianmu.agent.application.ReportArtifacts(reports);
        this.conversations = conversations;
    }
    public ToolDispatcher(SkillCatalog skills, RuntimeHarness harness, ToolRegistry registry, com.tianmu.agent.runtime.store.ReportStore reports) {
        this(skills,harness,registry,reports,null);
    }
    public ToolDispatcher(SkillCatalog skills, RuntimeHarness harness, ToolRegistry registry) { this(skills, harness, registry, null); }
    public List<ToolDefinition> definitions(Set<String> activeSkills) { return registry.definitions(activeSkills); }
    public List<ToolDefinition> definitions(RunState run) {
        return registry.definitions(run.execution().activeSkills(), snapshotTools(run)).stream()
                .filter(t -> permitted(t.name(), run)).toList();
    }
    private boolean permitted(String name, RunState run) {
        if(Set.of("analyze_feedback","create_feedback_report").contains(name) && !feedbackEnabled)return false;
        if(feedbackEnabled && run.task()!=null && com.tianmu.agent.application.AnalysisExecutionPolicy.permits("analyze_feedback",run.task().specification())
                && Set.of("query_audience","query_video_comments","query_danmaku","create_danmaku_report").contains(name))return false;
        return com.tianmu.agent.application.AnalysisExecutionPolicy.permits(name, run.task() == null ? null : run.task().specification());
    }
    private static Evidence bindFeedback(RunState run,com.tianmu.agent.core.FeedbackAnalysis.Asset asset){return com.tianmu.agent.application.FeedbackEvidenceBinding.bind(run,asset);}
    private Set<String> snapshotTools(RunState run) { return run.task() == null ? registry.names() : run.task().allowedTools(); }
    public void checkBatch(List<ToolCall> calls,RunState run) {
        if(calls.size()<2 || calls.size()>4)throw new AgentException("INVALID_DECISION","Native read batch must contain two to four calls");
        Set<ToolCall> unique=new HashSet<>();
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
            if (result == null || !call.name().equals(result.tool()) || result.data().containsKey("input_validation"))
                throw new AgentException("INVALID_TOOL_RESULT", "Tool result does not match the registered handler or uses host-reserved feedback");
            TraceDetails.capture("output", result, "TOOL_RESULT");
            return new Completed(result);
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
            throw new AgentException("INVALID_ARGUMENTS", "Tool arguments are invalid");
        }
    }
    private final class HostContext implements ToolContext {
        private final RunState run; private final AgentTool tool;
        private final com.tianmu.agent.application.ReportProgressService reportProgress;
        private final com.tianmu.agent.application.HistoryQueryService history;
        HostContext(RunState run, AgentTool tool) {
            this.run = run; this.tool = tool;
            this.reportProgress = new com.tianmu.agent.application.ReportProgressService(run, run.execution());
            this.history = new com.tianmu.agent.application.HistoryQueryService(conversations, run.sessionId, run.owner, run.turnSeq);
        }
        public UUID runId() { return run.id; }
        public AccessScope scope() { return run.owner; }
        public LocalDate businessDate() { return run.businessDate; }
        public String reportCacheVariant(){return run.task()==null?run.query():run.task().query();}
        public Integer remainingModelCalls(){return Math.max(0,run.modelCallLimit()-run.snapshot().totalModelCalls());}
        public void checkpoint() { run.checkpoint(); }
        public Map<String,Object> history(long before,int limit,String query) {
            run.checkpoint();
            return history.read(before,limit,query);
        }
        public com.tianmu.agent.core.AnalysisContract analysisContract(){return run.task()==null?null:run.task().specification().analysis();}
        public boolean reportRequiresVideo(){return run.task()!=null && run.task().specification().reading()!=null && run.task().specification().reading().includes(com.tianmu.agent.core.AnalysisContract.Source.VIDEO);}
        public List<Map<String, String>> listSkills() { return skills.list(); }
        public Skill activateSkill(String name) {
            if (!tool.canActivateSkills()) throw new AgentException("TOOL_CAPABILITY_DENIED", "Tool cannot activate skills");
            checkSkillRevision(name);
            Skill skill = skills.read(name);
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
        public void checkFeedback(com.tianmu.agent.core.FeedbackAnalysis.Request request){
            run.checkpoint();com.tianmu.agent.application.FeedbackTaskPolicy.require(run.task()==null?null:run.task().specification(),request);
            if(!run.owner.videoIds().contains(request.query().videoId()))throw new AgentException("FORBIDDEN","Feedback video outside scope");
        }
        public ToolResult analyzeFeedback(String videoEvidenceId,String analysisId){
            if(feedback==null || !feedbackEnabled || !tool.name().equals("analyze_feedback"))throw new AgentException("FEEDBACK_ANALYSIS_DISABLED","Background analysis unavailable");
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
        public void checkCommentQuery(com.tianmu.agent.core.DanmakuTypes.Query query) { business.checkCommentQuery(run, query); }
        public Evidence registerCommentEvidence(com.tianmu.agent.core.VideoComments.Dataset data) {
            if (!tool.name().equals("query_video_comments")) throw new AgentException("TOOL_CAPABILITY_DENIED", "Only the comment query may register comment evidence");
            business.checkCommentQuery(run, data.query());
            Evidence evidence = new Evidence("ev_" + UUID.randomUUID(), run.id, run.owner, null, null, null, data);
            run.execution().evidence().put(evidence); return evidence;
        }
        public int commentOffset(String id) { evidence(id); return run.execution().commentOffset(id); }
        public void advanceComments(String id, int expected, int next) {
            if (!tool.name().equals("query_video_comments")) throw new AgentException("TOOL_CAPABILITY_DENIED", "Only the comment query may advance its pages");
            Evidence value = evidence(id);
            if (value.comments() == null || next > value.comments().totalComments()) throw new AgentException("INVALID_COMMENT_OFFSET", "Invalid comment page");
            run.execution().advanceComments(id, expected, next);
        }
        public com.tianmu.agent.core.DanmakuClassification reportClassification(String id){
            return reportProgress.load(id);
        }
        public void saveReportClassification(String id,com.tianmu.agent.core.DanmakuClassification progress){
            if(!tool.name().equals("create_danmaku_report"))throw new AgentException("TOOL_CAPABILITY_DENIED","Only report tool may update its draft");
            reportProgress.save(id,progress);
        }
        public Evidence registerEvidence(MetricData data) { return business.registerEvidence(run, data); }
        public <T> T query(Function<CallControl, T> operation) { return harness.toolCall(run, operation); }
        public <T> T videoQuery(Function<CallControl, T> operation) { return harness.videoToolCall(run, operation); }
        public void checkVideoQuery(long videoId) { business.checkVideoQuery(run, videoId); }
        public void checkCreatorDraft(long videoId, String kind) {
            run.checkpoint();
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
