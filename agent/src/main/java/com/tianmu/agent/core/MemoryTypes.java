package com.tianmu.agent.core;

import java.time.Instant;
import java.util.*;

public final class MemoryTypes {
    private MemoryTypes() {}
    public record Message(UUID id, UUID runId, long sequence, String role, String text, Instant at) {}
    public record Focus(String request, UUID sourceRunId, String referenceVideoId,TaskTypes.Specification task) {
        public Focus(String request,UUID sourceRunId,String referenceVideoId){this(request,sourceRunId,referenceVideoId,null);}
        public Focus {
            if (request == null || request.isBlank() || request.length() > 500 || sourceRunId == null)
                throw new IllegalArgumentException("Invalid conversation focus");
        }
    }
    public record Persistent(UUID id,long version,String scope,String scopeKey,String kind,String text,List<UUID> sourceRefs,Instant expiresAt) {
        public Persistent(UUID id,long version,String scope,String scopeKey,String kind,String text){this(id,version,scope,scopeKey,kind,text,List.of(),null);}
        public Persistent { sourceRefs=sourceRefs==null?List.of():List.copyOf(sourceRefs); }
    }
    public record Compaction(UUID id,long revision,long fromSequence,long throughSequence,int messageCount,Map<String,Object> content){
        public Compaction { content=ImmutablePayload.map(content); }
    }
    public record View(long version, String olderSummary, List<Message> recent, boolean truncated,
                       RequestContext environment, Focus focus,List<Persistent> persistent,List<Compaction> compaction) {
        public View(long version,String summary,List<Message> recent,boolean truncated,RequestContext environment,Focus focus,List<Persistent> persistent){this(version,summary,recent,truncated,environment,focus,persistent,List.of());}
        public View(long version,String summary,List<Message> recent,boolean truncated,RequestContext environment,Focus focus) {
            this(version,summary,recent,truncated,environment,focus,List.of());
        }
        public View(long version, String summary, List<Message> recent, boolean truncated) {
            this(version, summary, recent, truncated, null, null);
        }
        public View { recent = List.copyOf(recent);persistent=persistent==null?List.of():List.copyOf(persistent);compaction=compaction==null?List.of():List.copyOf(compaction); }
        public View withContext(RequestContext environment, Focus focus) {
            return new View(version, olderSummary, recent, truncated, environment, focus,persistent,compaction);
        }
        public View withPersistent(List<Persistent> value){return new View(version,olderSummary,recent,truncated,environment,focus,value,compaction);}
        public View withRecent(List<Message> value){return new View(version,olderSummary,value,truncated,environment,focus,persistent,compaction);}
        public View withCompaction(List<Compaction> value){return new View(version,olderSummary,recent,truncated,environment,focus,persistent,value);}
        public static View empty() { return new View(0, "", List.of(), false); }
    }
}
