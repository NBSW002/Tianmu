package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.TaskTypes.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.resource.ResourceFiles;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.ToolCatalog;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import com.tianmu.agent.preparation.TaskMeaning;

/** Freezes task meaning and resource versions once, after readiness checks and before the loop. */
@Component
public class TaskAssembler {
    private final Map<String, String> versions;
    private final Map<String,String> legacyVersions;
    private final Map<String,String> canonicalLegacyVersions;
    private final Set<String> registrySnapshot;
    private final TaskMeaning meaning;
    private com.tianmu.agent.feedback.FeedbackPolicy feedbackPolicy=com.tianmu.agent.feedback.FeedbackPolicy.defaults();
    private com.tianmu.agent.feedback.FeedbackCompressionProperties compression=com.tianmu.agent.feedback.FeedbackCompressionProperties.defaults();
    @org.springframework.beans.factory.annotation.Autowired
    void compressionPolicy(com.tianmu.agent.feedback.FeedbackCompressionProperties policy){compression=policy;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.tianmu.agent.feedback.FeedbackCompression compressionRuntime;
    private String compressionRevision(){return compressionRuntime==null?compression.revision():compressionRuntime.revision();}
    public static final String COMPRESSION_RESOURCE="feedback:compression";
    private Map<String,String> pinCompression(Map<String,String> base,boolean feedback,Specification spec){
        if(!compression.enabled())return base;
        var pinned=new TreeMap<>(base);pinned.put(COMPRESSION_RESOURCE,compressionRevision());return Map.copyOf(pinned);
    }
    @org.springframework.beans.factory.annotation.Autowired
    void feedbackPolicy(com.tianmu.agent.feedback.FeedbackPolicy policy){feedbackPolicy=policy;}
    public TaskAssembler(SkillCatalog skills, PromptCatalog prompts, ToolCatalog tools) { this(skills, prompts, tools, TaskMeaning.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public TaskAssembler(SkillCatalog skills, PromptCatalog prompts, ToolCatalog tools, TaskMeaning meaning) {
        this.meaning = meaning;
        Map<String, String> loaded = new TreeMap<>();
        skills.names().forEach(name -> loaded.put("skill:" + name, skills.read(name).version()));
        skills.names().forEach(name -> loaded.put("skill-content:" + name, skills.fingerprint(name)));
        prompts.ids().forEach(id -> loaded.put("prompt:" + id, prompts.get(id).version()));
        tools.all().forEach((name, definition) -> loaded.put("tool:" + name, fingerprint(definition)));
        legacyVersions=Map.copyOf(loaded);
        skills.names().forEach(name->loaded.put("skill-content:"+name,skills.canonicalFingerprint(name)));
        canonicalLegacyVersions=Map.copyOf(loaded);
        loaded.put("manifest:format","2");
        prompts.ids().forEach(id -> loaded.put("prompt-content:" + id, contentFingerprint(prompts.text(id))));
        versions = Map.copyOf(loaded); registrySnapshot = Set.copyOf(tools.all().keySet());
    }
    public PreparedTask assemble(RunState run) {
        run.checkpoint();
        var preparation = Objects.requireNonNull(run.preparedRequest());
        var specification = preparation.specification() == null ? meaning.resolve(run.query(), run.businessDate, run.createdAt,run.memory()) : preparation.specification();
        boolean conversation = "unclassified".equals(specification.type())
                && Set.of(com.tianmu.agent.core.PreparationTypes.Intent.KNOWLEDGE, com.tianmu.agent.core.PreparationTypes.Intent.OTHER).contains(preparation.intent());
        if (conversation) specification = new Specification("conversation", Map.of(), Map.of());
        // Field provenance survives accepted rewrites: page values and Skill defaults remain distinct.
        boolean feedback=false;
        try{FeedbackTaskPolicy.request(specification);feedback=true;}
        catch(com.tianmu.agent.core.AgentException notAnalysis){if(!"TASK_CONSTRAINT_VIOLATION".equals(notAnalysis.code()))throw notAnalysis;}
        if(run.executionPolicy()!=null) {
            var initial=run.executionPolicy();initial.verify();
            var deadline=feedback?run.createdAt.plusMillis(initial.business().reportWindowMillis()):initial.initialDeadline();
            int turns=AnalysisExecutionPolicy.maxTurns(specification,initial.foreground().configuredTurns(),initial.foreground().maxCalls());
            // Reader flags are frozen at Run acceptance. Finalizing must never rewrite accepted resources.
            var resources=initial.business().resources();
            var fixed=initial.finalized(deadline,feedback?"FEEDBACK":"STANDARD",turns,resources);
            var task=new PreparedTask(run.id,run.message,preparation.query(),run.businessDate,"Asia/Shanghai",run.owner,preparation,specification,resources,registrySnapshot,deadline,run.modelCallLimit(),fixed.contentHash());
            run.bindPreparedTask(task,fixed);return task;
        }
        if(feedback)run.executionWindow(feedbackPolicy.timeout());
        return new PreparedTask(run.id, run.message, preparation.query(), run.businessDate, "Asia/Shanghai", run.owner,
                preparation, specification, pinCompression(legacyVersions,feedback,specification), registrySnapshot, run.deadline(), run.modelCallLimit());
    }
    public Map<String,String> resourceVersions(){return pinCompression(versions,false,null);}
    private static String contentFingerprint(String content) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content.replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public void verifyPolicy(com.tianmu.agent.core.ExecutionPolicySnapshot policy) {
        if(policy==null)return;policy.verify();
        if(!compatibleResources(policy.business().resources()))throw new com.tianmu.agent.core.AgentException("RESOURCE_VERSION_MISMATCH","Pinned preparation resources are unavailable");
    }
    private boolean compatibleResources(Map<String,String> stored) {
        if(stored.containsKey(COMPRESSION_RESOURCE)){
            if(!stored.get(COMPRESSION_RESOURCE).equals(compressionRevision()) || !compression.enabled())return false;
            var base=new TreeMap<>(stored);base.remove(COMPRESSION_RESOURCE);stored=base;
        }
        // Legacy manifests never recorded prompt content. Preserve their declared-version contract;
        // do not manufacture historical hashes. Format 2 requires every captured content fingerprint.
        return stored.containsKey("manifest:format") || stored.keySet().stream().anyMatch(key->key.startsWith("prompt-content:"))
                ?versions.equals(stored):com.tianmu.agent.resource.ResourceManifestCompatibility.legacy(stored,legacyVersions,canonicalLegacyVersions);
    }
    private String fingerprint(ToolDefinition definition) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(definition)));
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint tool definition"); }
    }
    public void verify(PreparedTask task){
        if(task==null || !compatibleResources(task.resourceVersions()) || !registrySnapshot.equals(task.allowedTools()))
            throw new com.tianmu.agent.core.AgentException("RESOURCE_VERSION_MISMATCH","Prepared task resources changed; restart with the new version");
    }
}
