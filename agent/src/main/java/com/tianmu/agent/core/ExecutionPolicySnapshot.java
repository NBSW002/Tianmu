package com.tianmu.agent.core;

import java.time.Instant;
import java.util.*;

/** Durable scalar policy, separate from usage counters and live authorization. Contains no secrets. */
public record ExecutionPolicySnapshot(String schemaVersion,String semanticsVersion,String contentHash,
        Instant acceptedAt,Instant initialDeadline,Instant effectiveDeadline,String windowReason,
        Foreground foreground,Timeouts timeouts,Context context,Business business,Model model) {
    public record Foreground(int maxCalls,long tokenLimit,long tokenReservation,int configuredTurns,int effectiveTurns,
            int consecutiveFailures,boolean finalizeEnabled,String turnRuleVersion) {}
    public record Timeouts(long modelMillis,long toolMillis,long finalizeMillis,long videoToolMillis,long faqMillis,
            long understandingMillis,long emotionMillis,long intentMillis,boolean deterministicRouting,boolean emotionEnabled) {}
    public record Context(int maxInputBytes,int maxHistoryBytes,int recentMessages,int recentBytes,
            String envelopeVersion,String projectionVersion,String slotPolicyVersion) {}
    public record Business(long reportWindowMillis,String conditionsVersion,String completionVersion,Map<String,String> resources) {
        public Business { resources=Map.copyOf(resources); }
    }
    public record Model(String id,String name,String revision,String adapterVersion,String preparationRevision) {}
    public ExecutionPolicySnapshot {
        Objects.requireNonNull(acceptedAt);Objects.requireNonNull(initialDeadline);Objects.requireNonNull(effectiveDeadline);
        Objects.requireNonNull(foreground);Objects.requireNonNull(timeouts);Objects.requireNonNull(context);Objects.requireNonNull(business);Objects.requireNonNull(model);
    }
    private String calculatedHash() {
        try {
            var data=new TreeMap<String,Object>();data.put("schema",schemaVersion);data.put("semantics",semanticsVersion);
            data.put("accepted",acceptedAt.toString());data.put("initial",initialDeadline.toString());data.put("effective",effectiveDeadline.toString());
            data.put("window",windowReason);data.put("foreground",foreground);data.put("timeouts",timeouts);data.put("context",context);data.put("business",business);data.put("model",model);
            var json=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(data)));
        } catch(Exception e){throw new IllegalStateException("Cannot hash execution policy",e);}
    }
    public ExecutionPolicySnapshot signed() {
        return new ExecutionPolicySnapshot(schemaVersion,semanticsVersion,calculatedHash(),acceptedAt,initialDeadline,effectiveDeadline,windowReason,foreground,timeouts,context,business,model);
    }
    public void verify() {
        if(!"1".equals(schemaVersion) || !"agent-execution-v1".equals(semanticsVersion)
                || !"analysis-turns-v1".equals(foreground.turnRuleVersion()) || !"1.0".equals(context.envelopeVersion())
                || !"chat_native_v1".equals(context.projectionVersion()) || !"slots-v1".equals(context.slotPolicyVersion())
                || !"conditions-v1".equals(business.conditionsVersion()) || !"completion-v1".equals(business.completionVersion())
                || !"chat-native-v1".equals(model.adapterVersion()))throw new AgentException("POLICY_VERSION_UNAVAILABLE","Execution policy version is unavailable");
        if(!Objects.equals(contentHash,calculatedHash()) || !initialDeadline.isAfter(acceptedAt) || !effectiveDeadline.isAfter(acceptedAt)
                || initialDeadline.isBefore(acceptedAt.plusMillis(1)) || initialDeadline.isAfter(acceptedAt.plusMillis(600000))
                || foreground.maxCalls()<1 || foreground.maxCalls()>500 || foreground.tokenLimit()<0 || foreground.tokenReservation()<1 || foreground.tokenReservation()>1000000000L
                || foreground.configuredTurns()<1 || foreground.configuredTurns()>100 || foreground.effectiveTurns()<1 || foreground.effectiveTurns()>100
                || foreground.consecutiveFailures()<1 || foreground.consecutiveFailures()>10
                || invalidMillis(timeouts.modelMillis(),600000) || invalidMillis(timeouts.toolMillis(),600000) || invalidMillis(timeouts.finalizeMillis(),600000) || invalidMillis(timeouts.videoToolMillis(),600000)
                || invalidMillis(timeouts.faqMillis(),30000) || invalidMillis(timeouts.understandingMillis(),30000) || invalidMillis(timeouts.emotionMillis(),30000) || invalidMillis(timeouts.intentMillis(),30000)
                || context.maxInputBytes()<1024 || context.maxInputBytes()>4194304 || context.maxHistoryBytes()<0 || context.maxHistoryBytes()>context.maxInputBytes()
                || context.recentMessages()<0 || context.recentMessages()>1000 || context.recentBytes()<0 || context.recentBytes()>4194304
                || business.reportWindowMillis()<60000 || business.reportWindowMillis()>21600000
                || model.id()==null || model.name()==null || model.revision()==null || model.preparationRevision()==null
                || !Set.of("PREPARATION","STANDARD","FEEDBACK").contains(Objects.toString(windowReason,""))
                || ("STANDARD".equals(windowReason) || "PREPARATION".equals(windowReason)) && !effectiveDeadline.equals(initialDeadline)
                || "FEEDBACK".equals(windowReason) && !effectiveDeadline.equals(acceptedAt.plusMillis(business.reportWindowMillis())))
            throw new AgentException("POLICY_SNAPSHOT_INVALID","Execution policy integrity is invalid");
    }
    private static boolean invalidMillis(long value,long maximum){return value<1 || value>maximum;}
    public ExecutionPolicySnapshot finalized(Instant deadline,String reason,int turns,Map<String,String> resources) {
        var f=foreground;
        return new ExecutionPolicySnapshot(schemaVersion,semanticsVersion,null,acceptedAt,initialDeadline,deadline,reason,
                new Foreground(f.maxCalls(),f.tokenLimit(),f.tokenReservation(),f.configuredTurns(),turns,f.consecutiveFailures(),f.finalizeEnabled(),f.turnRuleVersion()),
                timeouts,context,new Business(business.reportWindowMillis(),business.conditionsVersion(),business.completionVersion(),resources),model).signed();
    }
}
