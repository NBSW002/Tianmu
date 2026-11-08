package com.tianmu.agent.core;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import java.time.LocalDate;
import java.util.*;

/** Request preparation is data-only: it cannot grant tools or change the authenticated scope. */
public final class PreparationTypes {
    private PreparationTypes() {}
    public enum Emotion { NEUTRAL, POSITIVE, NEGATIVE, UNKNOWN }
    public enum Intent { VIDEO_METRIC, DANMAKU_REPORT, KNOWLEDGE, OTHER, UNKNOWN }
    public enum Route { FAQ, CLARIFY, HARNESS, DIRECT }
    public record IntentCandidate(String id, String label, String description, String boundary, String skill,
                                  Set<String> tools, List<String> requiredConditions, Intent category) {
        public IntentCandidate { tools=Set.copyOf(tools);requiredConditions=List.copyOf(requiredConditions); }
    }
    public record IntentRecognition(Intent intent, String primary, List<String> related, List<IntentCandidate> candidates) {
        public IntentRecognition {
            intent=Objects.requireNonNull(intent);related=List.copyOf(related);candidates=List.copyOf(candidates);
            if (related.size()>4 || new HashSet<>(related).size()!=related.size() || primary!=null && related.contains(primary)
                    || primary!=null && !primary.matches("[a-z][a-z0-9_-]{0,79}")) throw new IllegalArgumentException("Invalid capability selection");
        }
    }
    public record Input(String query, LocalDate businessDate, AccessScope scope, MemoryTypes.View memory, java.time.Instant requestedAt,
                        List<IntentCandidate> intentCandidates) {
        public Input { intentCandidates=intentCandidates==null?List.of():List.copyOf(intentCandidates); }
        public Input(String query, LocalDate date, AccessScope scope, MemoryTypes.View memory, java.time.Instant at) { this(query,date,scope,memory,at,List.of()); }
        public String targetedQuery() { return memory.environment()==null ? query : memory.environment().forParsing(query); }
        public Input(String query, LocalDate date, AccessScope scope, MemoryTypes.View memory) { this(query, date, scope, memory, date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()); }
        public Input(String query, LocalDate date, AccessScope scope) { this(query, date, scope, MemoryTypes.View.empty()); }
    }
    public record Clarification(boolean required, String question) {
        public Clarification {
            if (required && (question == null || question.isBlank() || question.length() > 2000))
                throw new IllegalArgumentException("A bounded clarification question is required");
            if (!required) question = null;
        }
    }
    /** Untrusted model candidates; PreparationService validates against server-derived task meaning. */
    public enum ClarificationIssue { NONE, UNSPECIFIED, MISSING_FIELDS, AMBIGUOUS_TARGET, CONTRADICTORY_CONSTRAINTS, UNKNOWN_GOAL, CONFIRMATION_REQUIRED }
    public record Understanding(boolean needsClarification, String clarificationQuestion,
                                Map<String, Object> conditions, String rewrittenQuery,ClarificationIssue issue,List<String> missingFields) {
        public Understanding(boolean clarify,String question,Map<String,Object> conditions,String rewrite){
            this(clarify,question,conditions,rewrite,clarify?ClarificationIssue.UNSPECIFIED:ClarificationIssue.NONE,List.of());
        }
        public Understanding {
            issue=issue==null?(needsClarification?ClarificationIssue.UNSPECIFIED:ClarificationIssue.NONE):issue;
            missingFields=missingFields==null?List.of():List.copyOf(missingFields);
            var allowed=Set.of("video_id","report_type","sources","time_range","date","metric","start","end","playback_range","output_style");
            if(missingFields.size()>8 || !allowed.containsAll(missingFields) || new HashSet<>(missingFields).size()!=missingFields.size()
                    || !needsClarification && (issue!=ClarificationIssue.NONE || !missingFields.isEmpty())
                    || needsClarification && issue==ClarificationIssue.NONE
                    || issue==ClarificationIssue.MISSING_FIELDS && missingFields.isEmpty())throw new IllegalArgumentException("Invalid clarification issue");
            conditions = ImmutablePayload.map(conditions);
            if (conditions.size() > 16 || conditions.entrySet().stream().anyMatch(e ->
                    !e.getKey().matches("[a-z][a-z0-9_]{0,63}") || e.getValue() == null
                    || !(e.getValue() instanceof String || e.getValue() instanceof Number || e.getValue() instanceof Boolean)
                    || e.getValue() instanceof String text && text.length() > 256))
                throw new IllegalArgumentException("Invalid understanding conditions");
            if (needsClarification) {
                new Clarification(true, clarificationQuestion);
                if (rewrittenQuery != null || !conditions.isEmpty())
                    throw new IllegalArgumentException("Clarification cannot carry executable candidates");
            } else if (clarificationQuestion != null) {
                throw new IllegalArgumentException("Executable understanding cannot request clarification");
            }
        }
        public static Understanding clarify(String question) { return new Understanding(true, question, Map.of(), null); }
        public static Understanding proceed(Map<String, Object> conditions, String query) {
            return new Understanding(false, null, conditions, query);
        }
    }
    public record FaqAnswer(String id, String version, String text) {
        public FaqAnswer {
            if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,80}") || version == null || !version.matches("[a-zA-Z0-9_.-]{1,40}")
                    || text == null || text.isBlank() || text.length() > 8000)
                throw new IllegalArgumentException("Invalid curated FAQ entry");
        }
    }
    /** Host reconciliation metadata. Model proposals cannot populate defaults or grant authority. */
    public record Resolution(String ruleTask,String effectiveTask,String reason,List<String> defaults,String clarificationReason,String rewriteState,String rewriteReason) {
        public Resolution(String ruleTask,String effectiveTask,String reason,List<String> defaults,String clarificationReason){this(ruleTask,effectiveTask,reason,defaults,clarificationReason,"SKIPPED","NO_CANDIDATE");}
        public Resolution withRewrite(String state,String why){return new Resolution(ruleTask,effectiveTask,reason,defaults,clarificationReason,state,why);}
        public Resolution { defaults=List.copyOf(defaults); }
    }
    public record PreparedRequest(String originalQuery, String query, Emotion emotion, Set<String> degradedStages, Set<String> skippedStages, Intent intent,
                                  TaskTypes.Specification specification, IntentRecognition recognition,Resolution resolution) {
        public PreparedRequest(String original,String query,Emotion emotion,Set<String> degraded,Set<String> skipped,Intent intent,TaskTypes.Specification specification,IntentRecognition recognition){
            this(original,query,emotion,degraded,skipped,intent,specification,recognition,null);
        }
        public PreparedRequest(String original, String query, Emotion emotion, Set<String> degraded, Set<String> skipped, Intent intent, TaskTypes.Specification specification) {
            this(original,query,emotion,degraded,skipped,intent,specification,null);
        }
        public PreparedRequest(String originalQuery, String query, Emotion emotion, Set<String> degraded, Set<String> skipped, Intent intent) {
            this(originalQuery, query, emotion, degraded, skipped, intent, null);
        }
        public PreparedRequest(String originalQuery, String query, Emotion emotion, Set<String> degraded, Set<String> skipped) {
            this(originalQuery, query, emotion, degraded, skipped, Intent.UNKNOWN);
        }
        public PreparedRequest(String originalQuery, String query, Emotion emotion, Set<String> degraded) { this(originalQuery, query, emotion, degraded, Set.of()); }
        public PreparedRequest { degradedStages = Set.copyOf(degradedStages); skippedStages = Set.copyOf(skippedStages); intent = intent == null ? Intent.UNKNOWN : intent; }
    }
    public record Result(Route route, PreparedRequest request, FaqAnswer faq, String clarification,com.tianmu.agent.core.AgentTypes.Answer reply) {
        public Result(Route route,PreparedRequest request,FaqAnswer faq,String clarification){this(route,request,faq,clarification,null);}
    }
}
