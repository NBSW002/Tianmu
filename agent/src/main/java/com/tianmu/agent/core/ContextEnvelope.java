package com.tianmu.agent.core;

import java.util.*;

/** Canonical logical model input. Provider transport and mutable runtime objects stay outside. */
public record ContextEnvelope(String schemaVersion,Phase phase,String policyVersion,List<Part> parts) {
    public enum Phase { PREPARATION, LOOP, EXTRACTION, COMPACTION, FINALIZATION }
    public enum Trust { POLICY, CAPABILITY, HOST_STATE, REFERENCE, CONVERSATION }
    public enum Slot {
        SYSTEM_PROMPT(Trust.POLICY), TOOL_CATALOG(Trust.CAPABILITY), SKILL_CATALOG(Trust.CAPABILITY),
        AGENT_CATALOG(Trust.CAPABILITY), EXECUTION_CONTEXT(Trust.HOST_STATE), FOCUS(Trust.HOST_STATE),
        MEMORY_CONTEXT(Trust.REFERENCE), ENVIRONMENT_CONTEXT(Trust.REFERENCE), REFERENCE_CONTEXT(Trust.REFERENCE),
        CURRENT_CONTEXT(Trust.CONVERSATION), RUNTIME_STATE(Trust.HOST_STATE), OUTPUT_CONTRACT(Trust.POLICY);
        private final Trust trust;
        Slot(Trust trust){this.trust=trust;}
        public Trust trust(){return trust;}
        public String key(){return name().toLowerCase(Locale.ROOT);}
    }
    public record Part(Slot slot,String revision,Map<String,Object> payload) {
        public Part {
            Objects.requireNonNull(slot);
            if(revision==null || !revision.matches("[A-Za-z0-9_.:/-]{1,128}"))throw new IllegalArgumentException("Invalid context revision");
            payload=ImmutablePayload.map(payload);
            if(payload.isEmpty())throw new IllegalArgumentException("Omit empty context slots");
        }
        public Trust trust(){return slot.trust();}
    }
    public ContextEnvelope {
        if(!"1.0".equals(schemaVersion) || phase==null || policyVersion==null || policyVersion.isBlank())throw new IllegalArgumentException("Invalid context envelope version");
        EnumMap<Slot,Part> ordered=new EnumMap<>(Slot.class);
        for(var part:Objects.requireNonNull(parts))if(ordered.putIfAbsent(part.slot(),part)!=null)throw new IllegalArgumentException("Duplicate context slot");
        if(!ordered.containsKey(Slot.SYSTEM_PROMPT) || !ordered.containsKey(Slot.CURRENT_CONTEXT))throw new IllegalArgumentException("System rules and current context are required");
        parts=List.copyOf(ordered.values());
    }
    public List<String> presentSlots(){return parts.stream().map(p->p.slot().key()).toList();}
}
