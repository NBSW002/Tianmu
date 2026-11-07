package com.tianmu.agent.core;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import java.time.*;
import java.util.*;

/** Versioned analysis assets. An asset reference is not a grant to read its contents. */
public final class FeedbackAnalysis {
    private FeedbackAnalysis() {}
    public enum Source { COMMENT, DANMAKU }
    public enum Grounding { FEEDBACK_ONLY, VIDEO_GROUNDED }
    public enum Phase { QUEUED, SNAPSHOTTING, ANALYZING, MERGING, VALIDATING, READY, WAITING_AUTH, FAILED, CANCELLED }
    public enum WorkKind { CLASSIFY, MERGE }
    public record Blob(String key, long bytes, String sha256) {
        public Blob {
            if (key == null || !key.matches("fb_[a-f0-9]{32}\\.json") || bytes < 1 || bytes > 1_048_576
                    || sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid analysis object reference");
        }
    }
    public record Request(DanmakuTypes.Query query, Set<Source> sources, Grounding grounding,
                          Long videoStartMs, Long videoEndMs, String rulesVersion,boolean forceReanalysis) {
        public Request(DanmakuTypes.Query q,Set<Source> s,Grounding g,Long start,Long end,String v){this(q,s,g,start,end,v,false);}
        public Request {
            sources = Set.copyOf(sources);
            Objects.requireNonNull(query); Objects.requireNonNull(grounding);
            if (sources.isEmpty() || rulesVersion == null || !rulesVersion.matches("[a-zA-Z0-9_.:-]{1,160}")) throw new IllegalArgumentException("Invalid feedback request");
            if ((videoStartMs == null) != (videoEndMs == null) || videoStartMs != null && (videoStartMs < 0 || videoEndMs <= videoStartMs)) throw new IllegalArgumentException("Invalid video interval");
            if (grounding == Grounding.FEEDBACK_ONLY && videoStartMs != null) throw new IllegalArgumentException("Unselected video interval");
        }
    }
    public record Unit(String id, Source source, String originalId, String text, String replyContext,
                       boolean author, Long playbackMs, String userId,Instant createdAt,Long likeCount) {
        public Unit(String id,Source source,String originalId,String text,String replyContext,boolean author,Long playbackMs,String userId,Instant createdAt){this(id,source,originalId,text,replyContext,author,playbackMs,userId,createdAt,null);}
        public Unit(String id,Source source,String originalId,String text,String replyContext,boolean author,Long playbackMs,String userId){this(id,source,originalId,text,replyContext,author,playbackMs,userId,null);}
        public Unit {
            if (id == null || !id.matches("[CD]:[1-9][0-9]{0,18}") || source == null || originalId == null
                    || !id.equals((source == Source.COMMENT ? "C:" : "D:") + originalId)
                    || text == null || text.length() > 16384 || replyContext == null || replyContext.length() > 65536
                    || playbackMs != null && (source != Source.DANMAKU || playbackMs < 0) || likeCount!=null && likeCount<0) throw new IllegalArgumentException("Invalid feedback unit");
        }
    }
    public record Counts(long comments, long danmaku, long authorComments, long authorDanmaku, long uniqueAudience) {
        public Counts {
            if (comments < 0 || danmaku < 0 || authorComments < 0 || authorComments > comments
                    || authorDanmaku < 0 || authorDanmaku > danmaku || uniqueAudience < 0 || uniqueAudience > comments + danmaku)
                throw new IllegalArgumentException("Invalid feedback counts");
        }
        public long total() { return Math.addExact(comments, danmaku); }
    }
    public record Snapshot(String version, Instant dataAsOf, Counts counts, List<Blob> chunks, String contentHash) {
        public Snapshot {
            chunks = List.copyOf(chunks); Objects.requireNonNull(dataAsOf); Objects.requireNonNull(counts);
            if (!"feedback-snapshot-v1".equals(version) || contentHash == null || !contentHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid feedback snapshot");
        }
    }
    /** members are batch group IDs for leaves and child-topic IDs for reducers. Counts are host-derived. */
    public record Topic(String id, String title, String observation, String recommendation,
                        List<String> members, long messages, long authorMessages) {
        public Topic {
            members = List.copyOf(members);
            if (id == null || id.isBlank() || id.length() > 120 || title == null || title.isBlank() || title.length() > 100
                    || observation == null || observation.length() > 2400 || recommendation == null || recommendation.length() > 1200
                    || members.isEmpty() || members.size() != new HashSet<>(members).size() || messages < 0 || authorMessages < 0 || authorMessages > messages)
                throw new IllegalArgumentException("Invalid analysis topic");
        }
    }
    public record ResultNode(String workId, WorkKind kind, List<Topic> topics, long covered, long authorCovered) {
        public ResultNode {
            topics = List.copyOf(topics);
            if (workId == null || kind == null || topics.size() > (kind==WorkKind.CLASSIFY?10:100) || covered < 0 || authorCovered < 0 || authorCovered > covered
                    || topics.stream().map(Topic::id).distinct().count() != topics.size()
                    || topics.stream().mapToLong(Topic::messages).sum() != covered
                    || topics.stream().mapToLong(Topic::authorMessages).sum() != authorCovered) throw new IllegalArgumentException("Invalid result node");
        }
    }
    public record Asset(UUID jobId, UUID producerRun, String tenantId, String userId, Request request,
                        Snapshot snapshot, List<Blob> roots, String model, String policyHash, VideoContent.Facts video,
                        List<Integer> observationCounts) {
        /** Assets written before the observation index remain readable. Null means unindexed, not empty. */
        public Asset(UUID jobId, UUID producerRun, String tenantId, String userId, Request request,
                     Snapshot snapshot, List<Blob> roots, String model, String policyHash, VideoContent.Facts video) {
            this(jobId,producerRun,tenantId,userId,request,snapshot,roots,model,policyHash,video,null);
        }
        public Asset {
            roots = List.copyOf(roots); Objects.requireNonNull(jobId); Objects.requireNonNull(producerRun);
            if(observationCounts!=null){
                observationCounts=List.copyOf(observationCounts);
                if(observationCounts.size()>10000 || observationCounts.stream().anyMatch(n->n<0 || n>10))
                    throw new IllegalArgumentException("Invalid observation index");
            }
            if (roots.size() > 1 || request == null || snapshot == null || model == null || policyHash == null
                    || request.grounding() == Grounding.VIDEO_GROUNDED && (video == null || video.videoId() != request.query().videoId())
                    || request.grounding() == Grounding.FEEDBACK_ONLY && video != null) throw new IllegalArgumentException("Invalid analysis asset");
        }
        public void requireCompatible(AccessScope caller, Request expected) {
            if (!tenantId.equals(caller.tenantId()) || !userId.equals(caller.userId()) || !caller.videoIds().contains(request.query().videoId()))
                throw new AgentException("RESULT_NOT_FOUND", "Analysis is outside the current authorization");
            if (!request.equals(expected)) throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Analysis differs from the frozen task");
        }
    }
    /** Run-local evidence exposes provenance and exact counts, never object storage keys or credentials. */
    public record Binding(UUID jobId, Request request, Counts counts, Instant dataAsOf, String snapshotHash,
                          String model, String policyHash) {
        public Binding {Objects.requireNonNull(jobId);Objects.requireNonNull(request);Objects.requireNonNull(counts);Objects.requireNonNull(dataAsOf);}
        public static Binding from(Asset asset){return new Binding(asset.jobId(),asset.request(),asset.snapshot().counts(),asset.snapshot().dataAsOf(),asset.snapshot().contentHash(),asset.model(),asset.policyHash());}
    }
}
