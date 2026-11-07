package com.tianmu.agent.memory;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Personal memory contracts; these are data, never task instructions or grants. */
public final class PersonalMemory {
    private PersonalMemory() {}
    public enum ScopeType { USER, VIDEO, TOPIC, SESSION }
    public enum Kind { PREFERENCE, GOAL, PROFILE, EPISODE, EXPERIENCE, SESSION_SUMMARY }
    public enum Mode { ON, READ_ONLY, OFF }
    public record Scope(ScopeType type, String key) {
        @JsonAnySetter public void rejectUnknown(String key,JsonNode value){throw invalid();}
        public Scope {
            if(type==null || key==null)throw invalid();
            if(type==ScopeType.USER && !key.equals("self"))throw invalid();
            if(type==ScopeType.VIDEO) {
                try { if(!key.matches("[1-9][0-9]{0,18}") || Long.parseLong(key)<=0)throw invalid(); }
                catch(NumberFormatException e){throw invalid();}
            }
            if(type==ScopeType.TOPIC || type==ScopeType.SESSION)try{UUID.fromString(key);}catch(RuntimeException e){throw invalid();}
        }
        public static Scope user(){return new Scope(ScopeType.USER,"self");}
        public void authorize(AccessScope owner) {
            PersonalMemory.owner(owner);
            if(type==ScopeType.VIDEO && !owner.readableVideoIds().contains(Long.parseLong(key)))throw missing();
        }
    }
    public record Content(Scope scope, Kind kind, String text, Instant expiresAt) {
        @JsonAnySetter public void rejectUnknown(String key,JsonNode value){throw invalid();}
        public Content {
            if(scope==null || kind==null || text==null)throw invalid();
            if(expiresAt!=null) {
                if(expiresAt.isBefore(Instant.parse("1000-01-01T00:00:00Z")) || expiresAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))throw invalid();
                expiresAt=expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            }
            text=text.strip();
            if(text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length>1024)throw invalid();
            if(text.matches("(?is).*(-----BEGIN .*PRIVATE KEY-----|Bearer\\s+[A-Za-z0-9._~+/-]{12,}|sk-[A-Za-z0-9_-]{20,}|(?:api[_-]?key|password|secret[_-]?key|密码|密钥|cookie|access[_-]?token)\\s*[:=：]\\s*\\S+).*"))throw invalid();
            for(int i=0;i<text.length();i++) {
                char c=text.charAt(i);
                if(Character.isHighSurrogate(c)) {
                    if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i)))throw invalid();
                } else if(Character.isLowSurrogate(c) || Character.isISOControl(c) && c!='\n' && c!='\t')throw invalid();
            }
        }
    }
    public record Settings(long sequence,long consentEpoch,long eraseEpoch,long version,Mode mode,boolean autoExtract) {
        public Settings(long sequence,long consentEpoch,long eraseEpoch,long version,Mode mode){this(sequence,consentEpoch,eraseEpoch,version,mode,false);}
    }
    public record Entry(UUID id,long version,long sequence,Content content,Instant createdAt) {}
    public record Receipt(UUID id,long version,long sequence,String action) {}
    public record Page(List<Entry> items,long nextBeforeSequence,boolean moreAvailable) {
        public Page{items=List.copyOf(items);}
    }
    public static void owner(AccessScope owner) {
        if(owner==null || owner.tenantId()==null || owner.userId()==null || owner.tenantId().isBlank()
                || owner.userId().isBlank() || owner.tenantId().length()>64 || owner.userId().length()>64)throw invalid();
    }
    public static void key(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_.:-]{1,128}"))throw invalid();
    }
    public static AgentException invalid(){return new AgentException("INVALID_REQUEST","Invalid personal memory request");}
    public static AgentException missing(){return new AgentException("MEMORY_NOT_FOUND","Memory is missing or unavailable");}
    public static AgentException conflict(){return new AgentException("MEMORY_VERSION_CONFLICT","Memory changed; reload before editing");}
}
