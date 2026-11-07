package com.tianmu.agent.preparation;

import com.tianmu.agent.core.StopReason;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Bounded fan-out/fan-in. Only the coordinator prepares or delivers; policy denial uses the terminal arbiter. */
@Service
@EnableConfigurationProperties(PreparationPolicy.class)
public class PreparationService implements AutoCloseable {
    @org.springframework.beans.factory.annotation.Autowired private com.tianmu.agent.application.ContextLifecycle contexts;
    private final FaqPort faq;
    private final PreparationModelPort model;
    private final PreparationPolicy policy;
    private final RuntimeHarness harness;
    private final List<QueryReadinessPolicy> validators;
    private final TaskMeaning meaning;
    @org.springframework.beans.factory.annotation.Autowired private com.tianmu.agent.application.PreparationContextResolver contextResolver=new com.tianmu.agent.application.PreparationContextResolver((com.tianmu.agent.query.VideoPagePort)null,(com.tianmu.agent.query.VideoSearchPort)null);
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor calls = com.tianmu.agent.config.ExecutionConfiguration.standalone("agent-preparation", 6, 6, 48);
    private boolean ownsExecutor = true;
    private IntentCandidateCatalog intentCandidates;
    private record Completed<T>(T value, Instant at) {}
    private record Branch<T>(FutureTask<Completed<T>> future, CancellationToken token, CancellationToken.Registration registration, Runnable submitted) {}
    public PreparationService(FaqPort faq, PreparationModelPort model, PreparationPolicy policy, RuntimeHarness harness) {
        this(faq, model, policy, harness, List.of());
    }
    public PreparationService(FaqPort faq, PreparationModelPort model, PreparationPolicy policy, RuntimeHarness harness,
                              List<QueryReadinessPolicy> validators) {
        this(faq, model, policy, harness, validators, TaskMeaning.defaults());
    }
    public PreparationService(FaqPort faq, PreparationModelPort model, PreparationPolicy policy, RuntimeHarness harness,
                              List<QueryReadinessPolicy> validators, TaskMeaning meaning) {
        this(faq, model, policy, harness, validators, meaning, null);
    }
    public PreparationService(FaqPort faq, PreparationModelPort model, PreparationPolicy policy, RuntimeHarness harness,
                              List<QueryReadinessPolicy> validators, TaskMeaning meaning, com.tianmu.agent.runtime.store.ConversationStore repository) {
        this.faq = faq; this.model = model; this.policy = policy; this.harness = harness; this.validators = List.copyOf(validators); this.meaning = meaning; this.contexts=new com.tianmu.agent.application.ContextLifecycle(repository,harness);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PreparationService(FaqPort faq, PreparationModelPort model, PreparationPolicy policy, RuntimeHarness harness,
            List<QueryReadinessPolicy> validators, TaskMeaning meaning, com.tianmu.agent.runtime.store.ConversationStore repository,
            @org.springframework.beans.factory.annotation.Qualifier("preparationExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor,
            IntentCandidateCatalog intentCandidates) {
        this(faq, model, policy, harness, validators, meaning, repository);
        this.intentCandidates=intentCandidates;
        calls.shutdown(); calls = executor; ownsExecutor = false;
    }
    public Result prepare(RunState run, String query) {
        var runPolicy=RunPolicies.preparation(run,policy);
        if(run.executionPolicy()!=null && !run.executionPolicy().model().preparationRevision().equals(model.revision()))throw new AgentException("MODEL_REVISION_UNAVAILABLE","Pinned preparation model is unavailable");
        contexts.load(run);
        Input input = new Input(query, run.businessDate, run.owner, run.memory().withContext(run.pageContext(),run.memory().focus()), run.createdAt);
        Set<String> degraded = new LinkedHashSet<>(), skipped = new LinkedHashSet<>();
        Optional<FaqAnswer> hit;
        if(contexts.memoryManagement(query)) {
            skip(run,"faq","MEMORY_MANAGEMENT");hit=Optional.empty();
        } else {
            Instant faqDeadline = deadline(run, runPolicy.faqTimeout());
            Branch<Optional<FaqAnswer>> lookup = submit(run, "faq", faqDeadline, input, c -> faq.find(input, c));
            try { hit = await(run, lookup, faqDeadline); }
            catch (PreparationUnavailable e) { degraded.add("faq"); hit = Optional.empty(); }
            finally { dispose(lookup); }
        }
        run.checkpoint();
        if (hit.isPresent()) {
            skip(run, "intent", "FAQ_HIT"); skip(run, runPolicy.deterministicRouting() ? "validate" : "clarification", "FAQ_HIT");
            skipEmotion(run, "FAQ_HIT");
            return new Result(Route.FAQ,
                new PreparedRequest(run.message, query, Emotion.UNKNOWN, degraded, Set.of("intent", "understanding", "emotion")), hit.get(), null);
        }

        var contextResolution=contextResolver.resolve(new com.tianmu.agent.application.PreparationContextResolver.Request(run.id,run.message,query,run.businessDate,run.owner,run.memory(),run.pageContext()),
                ()->run.callControl(deadline(run,runPolicy.understandingTimeout()),run.cancellation()),new com.tianmu.agent.application.PreparationContextResolver.Stages(){
                    public <T>T call(String name,java.util.function.Supplier<T> action){return harness.stage(run,name,null,null,Map.of(),action);}
                });
        run.memory(contextResolution.memory());
        String analysisQuery=contextResolution.analysisQuery();
        Input contextInput = new Input(analysisQuery, run.businessDate, run.owner, run.memory(), run.createdAt,
                intentCandidates==null?List.of():intentCandidates.candidates(run.owner));
        run.emit("PROGRESS", Map.of("stage", "preparing"));
        boolean resourceLookup = contextResolution.lookup()!=null;
        if(runPolicy.deterministicRouting() && resourceLookup){
            skipped.addAll(Set.of("intent","understanding","emotion","rewrite"));
            skip(run, "intent", "RESOURCE_LOOKUP"); skip(run, "validate", "RESOURCE_LOOKUP"); skipEmotion(run, "RESOURCE_LOOKUP");
            return new Result(Route.HARNESS,new PreparedRequest(run.message,query,Emotion.UNKNOWN,degraded,skipped,Intent.OTHER,
                    contextResolution.lookup()),null,null);
        }
        com.tianmu.agent.core.TaskTypes.Specification resolved;
        Clarification meaningClarification = null;
        try { resolved = meaning.resolve(run.query(), run.businessDate, run.createdAt,run.memory()); }
        catch (AgentException error) {
            if (!"AMBIGUOUS_ANALYSIS_SOURCES".equals(error.code())) throw error;
            if(runPolicy.deterministicRouting()) {
                skipped.addAll(Set.of("intent", "understanding", "emotion", "rewrite"));
                harness.stage(run, "agent.prepare.validate", null, null, rule(), () -> Optional.of(new Clarification(true, error.getMessage())));
                skip(run, "intent", "CLARIFICATION_REQUIRED"); skipEmotion(run, "CLARIFICATION_REQUIRED");
                return new Result(Route.CLARIFY, new PreparedRequest(run.message, query, Emotion.UNKNOWN, degraded, skipped), null, error.getMessage());
            }
            meaningClarification = new Clarification(true,error.getMessage());
            resolved = new com.tianmu.agent.core.TaskTypes.Specification("unclassified",Map.of(),Map.of());
        }
        final var specification = resourceLookup ? contextResolution.lookup() : resolved;
        if (!resourceLookup && meaningClarification == null && !specification.equals(meaning.resolve(query, run.businessDate, run.createdAt,run.memory())))
            throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Input transformation changed the task conditions");
        String validationQuery=analysisQuery;
        var currentFocus=run.memory().focus();
        if(currentFocus!=null && !currentFocus.request().equals(query) && AnalysisSourceSelection.mentions(query)
                && com.tianmu.agent.application.ConversationFocus.continuation(query))
            validationQuery=currentFocus.request()+"，"+com.tianmu.agent.core.DanmakuTarget.matcher(query).replaceAll("");
        var validationInput=new Input(validationQuery,run.businessDate,run.owner,run.memory(),run.createdAt);
        final var ruleClarification = meaningClarification;
        Optional<Clarification> required = harness.stage(run, runPolicy.deterministicRouting() ? "agent.prepare.validate" : "agent.prepare.readiness", null, null, rule(),
                () -> ruleClarification != null ? Optional.of(ruleClarification) : resourceLookup ? Optional.empty()
                        : validators.stream().map(v -> v.validate(validationInput)).flatMap(Optional::stream).filter(Clarification::required).findFirst());
        if (runPolicy.deterministicRouting()) {
            if (required.isPresent()) {
                skipped.addAll(Set.of("intent", "understanding", "emotion", "rewrite"));
                skip(run, "intent", "CLARIFICATION_REQUIRED"); skipEmotion(run, "CLARIFICATION_REQUIRED");
                return new Result(Route.CLARIFY, new PreparedRequest(run.message, query, Emotion.UNKNOWN, degraded, skipped), null, required.get().question());
            }
            Intent known = harness.stage(run, "agent.prepare.intent", null, null, rule(), () -> switch (specification.type()) {
                case "creator.draft" -> Intent.OTHER;
                case "video.metric" -> Intent.VIDEO_METRIC;
                case "danmaku.report", "audience.report" -> Intent.DANMAKU_REPORT;
                case "video.content" -> Intent.OTHER;
                case "danmaku.overview" -> Intent.OTHER;
                case "comments.analysis" -> Intent.OTHER;
                default -> Intent.OTHER;
            });
            if (known != Intent.UNKNOWN) {
                skipEmotion(run, "RULE_ROUTE");
                skipped.addAll(Set.of("understanding", "emotion", "rewrite"));
                bindFocus(run,specification);
                return new Result(Route.HARNESS, new PreparedRequest(run.message, query, Emotion.UNKNOWN, degraded, skipped, known, specification), null, null);
            }
        }
        Instant end = deadline(run, runPolicy.understandingTimeout());
        Instant intentEnd = deadline(run, runPolicy.intentTimeout());
        Instant emotionEnd = deadline(run, runPolicy.emotionTimeout());
        // All branches read the same immutable snapshot. Only this coordinator merges results.
        Branch<Understanding> understanding = submit(run, "clarification", end, contextInput, c -> model.clarification(contextInput, c));
        Branch<IntentRecognition> intent = submit(run, "intent", intentEnd, contextInput,
                c -> IntentCandidateCatalog.validate(contextInput,model.recognizeIntent(contextInput,c)));
        if (!runPolicy.emotionEnabled()) skipEmotion(run, "CONFIG_DISABLED");
        Branch<Emotion> emotion = runPolicy.emotionEnabled() ? submit(run, "emotion", emotionEnd, contextInput, c -> model.emotion(contextInput, c)) : null;
        final var initialRequired=required;
        return harness.stage(run,"agent.prepare.merge",null,null,Map.of("node.type","ROUTING"),()->{
        var requiredConditions=initialRequired;
        try {
            IntentRecognition recognition;
            try { recognition = await(run, intent, intentEnd); }
            catch (PreparationUnavailable e) { degraded.add("intent"); recognition = new IntentRecognition(Intent.UNKNOWN,null,List.of(),contextInput.intentCandidates()); }
            Intent recognized=recognition.intent();
            Understanding result;
            try { result = await(run, understanding, end); }
            catch (PreparationUnavailable e) {
                degraded.add("understanding");
                result = Understanding.clarify("前置识别暂不可用，请稍后重试。");
            }
            var effective=specification;
            String mergeReason="RULE_MATCHED",clarificationReason="NONE";

            if(requiredConditions.isEmpty() && specification.type().equals("unclassified")) {
                try {
                    var candidate=meaning.reportCandidate(contextInput,recognition);
                    if(candidate.isPresent()){effective=candidate.get();mergeReason="CAPABILITY_DEFAULTS";}
                } catch(AgentException e) {
                    if(!e.code().equals("AMBIGUOUS_ANALYSIS_SOURCES"))throw e;
                    requiredConditions=Optional.of(new Clarification(true,e.getMessage()));
                }
            }
            authorizeTask(effective,run.owner);
            var defaults=defaultFields(effective,contextInput);
            if(requiredConditions.isEmpty() && (degraded.contains("understanding") || degraded.contains("intent") && effective.type().equals("unclassified")))
                throw new AgentException("MODEL_UNAVAILABLE","前置识别暂不可用，请稍后重试。");
            if (requiredConditions.isPresent()) {
                result=Understanding.clarify(requiredConditions.get().question());clarificationReason="REQUIRED_CONDITIONS";
            } else if (recognized == Intent.UNKNOWN && effective.type().equals("unclassified")) {
                result=Understanding.clarify("你希望完成什么任务？请简要说明目标。");clarificationReason="UNKNOWN_GOAL";
            } else if(effective.type().equals("unclassified") && !validIntent(effective.type(),recognized)) {
                result=Understanding.clarify(recognized==Intent.DANMAKU_REPORT?"请说明要生成哪类报告，以及要处理的视频。":"要查询哪个视频的什么数据？请补充必要条件。");
                clarificationReason="UNRESOLVED_TASK";
            } else {
                // A parser miss is not a contradiction. An authoritative original/page-derived
                // task also does not become ambiguous because a model misclassified or omitted it.
                if(!validIntent(effective.type(),recognized) && !effective.type().equals("unclassified")){
                    recognized=intentFor(effective.type());mergeReason="RULE_PRESERVED";
                    recognition=hostSelection(recognition,effective.type(),recognized);
                }
                if(result.needsClarification() && result.issue()==ClarificationIssue.MISSING_FIELDS
                        && !effective.type().equals("unclassified") && missingSatisfied(result,effective)){
                    result=Understanding.proceed(effective.conditions(),null);mergeReason="DEFAULTS_SATISFIED";
                } else if(!result.needsClarification() && !resourceLookup && !validConditions(effective.conditions(),result.conditions())){
                    // Discard unsupported model values; never substitute them into the frozen task.
                    if(!effective.type().equals("unclassified")){
                        result=Understanding.proceed(effective.conditions(),null);mergeReason="MODEL_CONDITIONS_REJECTED";
                    }else{
                        result=Understanding.clarify("请补充这项任务需要的具体条件。");clarificationReason="UNRESOLVED_CONDITIONS";
                    }
                }
                if(result.needsClarification() && clarificationReason.equals("NONE"))clarificationReason=result.issue().name();
            }
            var resolution=new Resolution(specification.type(),effective.type(),mergeReason,defaults,clarificationReason);
            if (result.needsClarification()) {
                resolution=resolution.withRewrite("SKIPPED","CLARIFICATION_REQUIRED");
                skipped.add("rewrite");
                Emotion feeling = readyEmotion(run, emotion, emotionEnd, degraded, skipped);
                return new Result(Route.CLARIFY, new PreparedRequest(run.message, query, feeling, degraded, skipped, recognized,null,recognition,resolution), null, result.clarificationQuestion());
            }
            String candidate=result.rewrittenQuery(),rewritten=query;
            if(candidate!=null && !candidate.equals(query)) {
                boolean accepted=false;
                if(safeRewrite(query,candidate) && !resourceLookup && !effective.type().equals("unclassified"))try{
                    var rewrittenInput=new Input(candidate,run.businessDate,run.owner,run.memory(),run.createdAt,contextInput.intentCandidates());
                    var proposed=meaning.resolve(candidate,run.businessDate,run.createdAt,run.memory());
                    if(proposed.type().equals("unclassified"))proposed=meaning.reportCandidate(rewrittenInput,recognition).orElse(proposed);
                    accepted=sameTask(effective,proposed);
                }catch(AgentException|IllegalArgumentException rejected){ /* Untrusted rewrite never aborts the original request. */ }
                if(accepted){rewritten=candidate;resolution=resolution.withRewrite("ACCEPTED","TASK_EQUIVALENT");}
                else{degraded.add("rewrite");resolution=resolution.withRewrite("REJECTED","SEMANTIC_CHANGE_OR_UNVERIFIABLE");}
            }
            Emotion feeling = readyEmotion(run, emotion, emotionEnd, degraded, skipped);
            run.checkpoint();
            bindFocus(run,effective);
            return new Result(Route.HARNESS, new PreparedRequest(run.message, rewritten, feeling, degraded, skipped, recognized, effective,recognition,resolution), null, null);
        } finally { dispose(understanding); dispose(intent); dispose(emotion); }
        });
    }
    private Map<String, Object> rule() {
        return Map.of("preparation.method", "RULE", "preparation.state", "EXECUTED", "node.type", "ROUTING");
    }
    private void skipEmotion(RunState run, String reason) {
        skip(run, "emotion", RunPolicies.preparation(run,policy).emotionEnabled() ? reason : "CONFIG_DISABLED");
    }
    private void skip(RunState run, String name, String reason) {
        String state = reason.equals("CONFIG_DISABLED") ? "DISABLED" : "SKIPPED";
        harness.stage(run, "agent.prepare." + name, null, null,
                Map.of("preparation.method", "NONE", "preparation.state", state, "preparation.reason", reason,
                        "node.type", "ROUTING", "node.status", state), () -> null);
    }
    private boolean validIntent(String type, Intent intent) {
        if(intent==Intent.UNKNOWN)return false;
        return switch (type) {
            case "danmaku.report", "audience.report", "comments.analysis", "danmaku.overview" -> intent == Intent.DANMAKU_REPORT;
            case "resource.lookup" -> intent != Intent.UNKNOWN;
            case "video.metric" -> intent == Intent.VIDEO_METRIC;
            default -> intent != Intent.VIDEO_METRIC && intent != Intent.DANMAKU_REPORT;
        };
    }
    private void authorizeTask(com.tianmu.agent.core.TaskTypes.Specification spec,com.tianmu.agent.core.AgentTypes.AccessScope owner){
        if(!(spec.conditions().get("video_id") instanceof Number video))return;
        if(!owner.readableVideoIds().contains(video.longValue()))throw new AgentException("FORBIDDEN","Video is outside the authenticated scope");
        if(spec.reading()!=null && !spec.reading().textSources().isEmpty() && !owner.videoIds().contains(video.longValue()))throw new AgentException("FORBIDDEN","Feedback access requires author permission for the selected video");
    }
    private boolean missingSatisfied(Understanding proposal,com.tianmu.agent.core.TaskTypes.Specification spec){
        return proposal.missingFields().stream().allMatch(field->switch(field){
            case "report_type"->spec.reading()!=null && spec.reading().reportRequired();
            case "sources"->spec.reading()!=null;
            case "time_range"->spec.conditions().containsKey("start") && spec.conditions().containsKey("end");
            case "output_style"->spec.reading()!=null && spec.reading().reportRequired();
            case "playback_range"->spec.conditions().containsKey("start_ms") && spec.conditions().containsKey("end_ms");
            default->spec.conditions().containsKey(field);
        });
    }
    private boolean sameTask(com.tianmu.agent.core.TaskTypes.Specification a,com.tianmu.agent.core.TaskTypes.Specification b){
        return a.type().equals(b.type()) && a.conditions().equals(b.conditions()) && Objects.equals(a.reading(),b.reading());
    }
    private Intent intentFor(String type){return switch(type){
        case "video.metric"->Intent.VIDEO_METRIC;
        case "audience.report","danmaku.report","comments.analysis","danmaku.overview"->Intent.DANMAKU_REPORT;
        default->Intent.OTHER;
    };}
    private IntentRecognition hostSelection(IntentRecognition original,String type,Intent intent){
        String primary=switch(type){case "video.metric"->"video-metrics";case "audience.report"->"audience-report";case "danmaku.report","danmaku.overview"->"danmaku-analysis";case "comments.analysis"->"comment-analysis";case "video.content"->"video-content";default->null;};
        if(primary==null || original.candidates().stream().noneMatch(c->c.id().equals(primary)))return new IntentRecognition(intent,null,List.of(),original.candidates());
        return new IntentRecognition(intent,primary,List.of(),original.candidates());
    }
    private List<String> defaultFields(com.tianmu.agent.core.TaskTypes.Specification spec,Input input){
        var fields=new ArrayList<String>();
        if(spec.conditions().containsKey("video_id") && !com.tianmu.agent.core.DanmakuTarget.matcher(input.query()).find() && input.memory().environment()!=null)fields.add("video_id");
        if(spec.reading()!=null && spec.reading().reportRequired())fields.add("report_template");
        if(spec.reading()!=null && !AnalysisSourceSelection.mentions(input.query()))fields.add("sources");
        if(spec.typed() instanceof com.tianmu.agent.core.TaskConditions.Report report && report.query().allTime())fields.add("time_range");
        return List.copyOf(fields);
    }
    private void bindFocus(RunState run,com.tianmu.agent.core.TaskTypes.Specification specification) {
        contexts.bindFocus(run,specification);
    }
    /** Model candidates never grant scope or supply missing server-side task conditions. */
    private boolean validConditions(Map<String, Object> expected, Map<String, Object> proposed) {
        if (!expected.keySet().containsAll(proposed.keySet())) return false;
        return proposed.entrySet().stream().allMatch(proposal -> {
            var e=Map.entry(proposal.getKey(),expected.get(proposal.getKey()));
            Object actual = proposed.get(e.getKey());
            if (Set.of("start", "end").contains(e.getKey()) && e.getValue() instanceof String expectedTime && actual instanceof String candidateTime) {
                try { return LocalDateTime.parse(expectedTime).equals(LocalDateTime.parse(candidateTime)); }
                catch (DateTimeException ignored) { return false; }
            }
            if (e.getValue() instanceof Number number && actual instanceof Number candidate)
                return new java.math.BigDecimal(number.toString()).compareTo(new java.math.BigDecimal(candidate.toString())) == 0;
            return Objects.equals(e.getValue(), actual);
        });
    }
    private Emotion readyEmotion(RunState run, Branch<Emotion> branch, Instant end, Set<String> degraded, Set<String> skipped) {
        if (branch == null) { skipped.add("emotion"); return Emotion.UNKNOWN; }
        try { return await(run, branch, end); }
        catch (PreparationUnavailable e) { degraded.add("emotion"); return Emotion.UNKNOWN; }
    }
    private Instant deadline(RunState run, Duration timeout) {
        Instant local = Instant.now().plus(timeout);
        return local.isBefore(run.deadline()) ? local : run.deadline();
    }
    private <T> Branch<T> submit(RunState run, String name, Instant deadline, Input input, Function<CallControl, T> operation) {
        CancellationToken token = new CancellationToken();
        CallControl control = run.callControl(deadline, token);
        long submittedAt = System.nanoTime();
        long timeoutMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        FutureTask<Completed<T>> task = new FutureTask<>(() -> {
            // Trace receives the actual business result, not the internal deadline wrapper.
            java.util.concurrent.atomic.AtomicReference<Instant> completedAt = new java.util.concurrent.atomic.AtomicReference<>();
            T value = harness.stage(run, "agent.prepare." + name, null, null,
                    Map.of("preparation.method", name.equals("faq") ? "LOOKUP" : "MODEL", "preparation.state", "EXECUTED", "execution.queue_ms", Math.max(0, (System.nanoTime() - submittedAt) / 1_000_000), "execution.timeout_ms", timeoutMs), () -> {
                com.tianmu.agent.harness.trace.TraceDetails.input(input);
                control.checkpoint();
                if (!Instant.now().isBefore(deadline)) throw new PreparationUnavailable();
                try {
                    if (!name.equals("faq")) run.preparationCallStarted();
                    T result = operation.apply(control);
                    completedAt.set(Instant.now());
                    return result;
                } catch (AgentException e) {
                    if (!Set.of("CANCELLED", "DEADLINE_EXCEEDED", "MODEL_UNAVAILABLE", "MODEL_ERROR", "MODEL_CALL_TIMEOUT",
                            "INVALID_MODEL_RESPONSE", "INVALID_DECISION").contains(e.code())) run.fail(e.code());
                    throw e;
                }
            });
            return new Completed<>(value, completedAt.get());
        });
        Runnable submitted = harness.contextual(task);
        CancellationToken.Registration registration = run.cancellation().onCancel(() -> {
            task.cancel(true); token.cancel(run.cancellation().reason()); calls.getThreadPoolExecutor().remove(submitted);
        });
        try { calls.execute(submitted); }
        catch (RejectedExecutionException e) { task.cancel(false); token.cancel(StopReason.ADAPTER_TIMEOUT); }
        return new Branch<>(task, token, registration, submitted);
    }
    private <T> T await(RunState run, Branch<T> branch, Instant end) {
        run.checkpoint();
        try {
            // A completed sibling is still usable even if another branch consumed the shared deadline.
            Completed<T> result = branch.future().isDone() ? branch.future().get()
                    : branch.future().get(Math.max(1, Duration.between(Instant.now(), end).toNanos()), TimeUnit.NANOSECONDS);
            run.checkpoint();
            if (result.value() == null || result.at().isAfter(end)) throw new PreparationUnavailable();
            return result.value();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); run.cancel(StopReason.SHUTDOWN); run.checkpoint();
            throw new PreparationUnavailable();
        } catch (ExecutionException e) {
            run.checkpoint();
            // Authorization and policy failures must never be silently degraded.
            if (e.getCause() instanceof AgentException a) {
                if (Set.of("MODEL_UNAVAILABLE", "MODEL_ERROR", "MODEL_CALL_TIMEOUT", "INVALID_MODEL_RESPONSE", "INVALID_DECISION").contains(a.code()))
                    throw new PreparationUnavailable();
                throw a;
            }
            throw new PreparationUnavailable();
        } catch (TimeoutException | CancellationException e) {
            run.checkpoint(); throw new PreparationUnavailable();
        }
    }
    private void dispose(Branch<?> branch) {
        if (branch == null) return;
        branch.registration().close();
        if (!branch.future().isDone()) { branch.future().cancel(true); branch.token().cancel(StopReason.ADAPTER_TIMEOUT); }
        if (branch.future().isCancelled()) calls.getThreadPoolExecutor().remove(branch.submitted());
    }
    /** Cheap guard only; semantic preservation still requires adapter evaluation. Never changes authorization. */
    static boolean safeRewrite(String original, String rewrite) {
        if (rewrite == null || rewrite.isBlank() || rewrite.length() > 2000) return false;
        if (!numbers(original).equals(numbers(rewrite))) return false;
        for (String anchor : List.of("昨天", "今天", "前天", "上周", "本周", "上月", "本月", "弹幕", "播放", "点赞", "评论", "不", "只", "仅", "排除", "全部", "所有", "发布", "发送", "删除", "授权", "观众", "反馈", "画面", "字幕", "音轨", "语音"))
            if (original.contains(anchor) != rewrite.contains(anchor)) return false;
        return true;
    }
    private static List<String> numbers(String text) {
        return Pattern.compile("\\d+").matcher(text).results().map(m -> m.group()).toList();
    }
    private static class PreparationUnavailable extends RuntimeException {}
    @Override @PreDestroy public void close() { if (ownsExecutor) calls.shutdown(); }
}
