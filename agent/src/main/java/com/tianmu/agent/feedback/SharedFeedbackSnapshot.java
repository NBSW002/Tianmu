package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Host-only materialization. Both source tools read partitions of ONE source export.
 * The caller must durably bind the returned manifest to its operation before dispatching children.
 * This class neither schedules jobs nor makes model calls.
 */
public final class SharedFeedbackSnapshot {
    private static final int CHUNK_BYTES = 524288;
    private static final String VERSION = "shared-feedback-snapshot-v1";
    private final FeedbackSnapshotPort source;
    private final FeedbackObjects objects;
    private final FeedbackPolicy policy;

    public record Partition(Source source, Counts counts, List<Blob> chunks) {
        public Partition {
            Objects.requireNonNull(source); Objects.requireNonNull(counts);
            chunks = List.copyOf(chunks);
            if (source == Source.COMMENT && counts.danmaku() != 0
                    || source == Source.DANMAKU && counts.comments() != 0)
                throw new IllegalArgumentException("Mixed source partition");
        }
    }
    public record Manifest(String version, UUID runId, String tenantId, String userId,
                           Request request, Instant dataAsOf, Counts counts, Map<Source, Partition> partitions) {
        public Manifest {
            if (!VERSION.equals(version)) throw new IllegalArgumentException("Unknown snapshot version");
            Objects.requireNonNull(runId); Objects.requireNonNull(tenantId); Objects.requireNonNull(userId);
            Objects.requireNonNull(request); Objects.requireNonNull(dataAsOf); Objects.requireNonNull(counts);
            partitions = Map.copyOf(partitions);
            if (!partitions.keySet().equals(request.sources())) throw new IllegalArgumentException("Missing source partition");
            long comments=0, danmaku=0, ac=0, ad=0;
            for (var entry : partitions.entrySet()) {
                var p=entry.getValue();
                if (entry.getKey()!=p.source()) throw new IllegalArgumentException("Incorrect partition key");
                comments+=p.counts().comments(); danmaku+=p.counts().danmaku();
                ac+=p.counts().authorComments(); ad+=p.counts().authorDanmaku();
            }
            if (comments!=counts.comments() || danmaku!=counts.danmaku()
                    || ac!=counts.authorComments() || ad!=counts.authorDanmaku())
                throw new IllegalArgumentException("Snapshot count mismatch");
        }
    }
    public record Chunk(List<Unit> units) {
        public Chunk { units=List.copyOf(units); }
    }

    public SharedFeedbackSnapshot(FeedbackSnapshotPort source, FeedbackObjects objects, FeedbackPolicy policy) {
        this.source=Objects.requireNonNull(source); this.objects=Objects.requireNonNull(objects);
        this.policy=Objects.requireNonNull(policy);
    }

    /** No reference is returned until all sources and the receipt have been verified.
     * registeredJobId must be a durable retention owner in the existing feedback job store;
     * using a run ID here would let the object collector delete live snapshot chunks.
     */
    public Blob materialize(UUID registeredJobId, UUID runId, AccessScope owner, Request request, CallControl control) {
        Objects.requireNonNull(registeredJobId); Objects.requireNonNull(runId);
        check(control); authorize(owner,request);
        var builders=new EnumMap<Source,Builder>(Source.class);
        request.sources().forEach(s->builders.put(s,new Builder(registeredJobId,s)));
        var ids=new HashSet<String>(); var total=new Counter(); long[] bytes={0};
        var receipt=source.export(request,owner,control,rows->{
            check(control);
            for(var unit:rows) {
                check(control);
                if(!request.sources().contains(unit.source()) || !ids.add(unit.id()))
                    throw new AgentException("INVALID_EVIDENCE","Unselected or duplicate snapshot member");
                verifyTime(unit,request);
                bytes[0]+=objects.bytes(unit);
                if(bytes[0]>policy.maxSnapshotBytes()) throw new AgentException("FULL_ANALYSIS_TOO_LARGE","Snapshot byte quota exceeded");
                total.accept(unit); builders.get(unit.source()).accept(unit);
            }
        });
        check(control);
        if(receipt==null || receipt.dataAsOf()==null || !total.counts().equals(receipt.counts()))
            throw new AgentException("INVALID_EVIDENCE","Snapshot receipt differs from exported members");
        authorize(owner,request);
        var partitions=new EnumMap<Source,Partition>(Source.class);
        builders.forEach((s,b)->partitions.put(s,b.finish()));
        check(control);
        return objects.put(registeredJobId,new Manifest(VERSION,runId,owner.tenantId(),owner.userId(),
                request,receipt.dataAsOf(),total.counts(),partitions));
    }

    public Manifest resolve(Blob ref, UUID runId, AccessScope caller, Request expected, CallControl control) {
        check(control); authorize(caller,expected);
        var manifest=objects.get(ref,Manifest.class);
        if(!manifest.runId().equals(runId) || !manifest.tenantId().equals(caller.tenantId())
                || !manifest.userId().equals(caller.userId()))
            throw new AgentException("RESULT_NOT_FOUND","Snapshot is outside the current run and owner");
        if(!manifest.request().equals(expected))
            throw new AgentException("TASK_CONSTRAINT_VIOLATION","Snapshot differs from frozen task");
        check(control); return manifest;
    }

    /** Consumers must stage results until this method returns successfully; each chunk is bounded. */
    public Partition read(Blob ref, UUID runId, AccessScope caller, Request expected, Source selected,
                          CallControl control, Consumer<List<Unit>> consumer) {
        var manifest=resolve(ref,runId,caller,expected,control);
        var partition=manifest.partitions().get(selected);
        if(partition==null) throw new AgentException("TASK_CONSTRAINT_VIOLATION","Source was not selected");
        var seen=new HashSet<String>(); var counts=new Counter(); long bytes=0;
        for(var blob:partition.chunks()) {
            check(control); authorize(caller,expected);
            var chunk=objects.get(blob,Chunk.class);
            for(var unit:chunk.units()) {
                if(unit.source()!=selected || !seen.add(unit.id()))
                    throw new AgentException("STORAGE_CORRUPT","Invalid source partition member");
                verifyTime(unit,expected); counts.accept(unit); bytes+=objects.bytes(unit);
                if(bytes>policy.maxSnapshotBytes())
                    throw new AgentException("FULL_ANALYSIS_TOO_LARGE","Partition exceeds read quota");
            }
            check(control); consumer.accept(chunk.units());
        }
        if(!counts.counts().equals(partition.counts()))
            throw new AgentException("STORAGE_CORRUPT","Partition counts differ from members");
        check(control); authorize(caller,expected); return partition;
    }

    private void authorize(AccessScope caller,Request request) {
        if(!caller.videoIds().contains(request.query().videoId()))
            throw new AgentException("FORBIDDEN","Video outside current authorization");
        source.authorize(caller,request.query().videoId());
    }
    private static void check(CallControl control) {
        control.checkpoint();
        if(!Instant.now().isBefore(control.deadline()))
            throw new AgentException("DEADLINE_EXCEEDED","Snapshot execution deadline exceeded");
    }
    private static void verifyTime(Unit unit,Request request) {
        var start=request.query().start().atZone(DanmakuTypes.ZONE).toInstant();
        var end=request.query().end().atZone(DanmakuTypes.ZONE).toInstant();
        if(unit.createdAt()==null || unit.createdAt().isBefore(start) || !unit.createdAt().isBefore(end))
            throw new AgentException("INVALID_EVIDENCE","Missing or out-of-range creation time");
    }
    private final class Builder {
        private final UUID run; private final Source selected;
        private final List<Unit> pending=new ArrayList<>(); private final List<Blob> chunks=new ArrayList<>();
        private final Counter counts=new Counter(); private int bytes;
        Builder(UUID run,Source selected){this.run=run;this.selected=selected;}
        void accept(Unit unit) {
            int size=objects.bytes(unit);
            if(size>CHUNK_BYTES-32) throw new AgentException("FEEDBACK_UNIT_TOO_LARGE","Snapshot member too large");
            if(bytes+size+pending.size()+32>CHUNK_BYTES) flush();
            pending.add(unit);bytes+=size;counts.accept(unit);
        }
        void flush(){if(!pending.isEmpty()){chunks.add(objects.put(run,new Chunk(pending)));pending.clear();bytes=0;}}
        Partition finish(){flush();return new Partition(selected,counts.counts(),chunks);}
    }
    private static final class Counter {
        long comments,danmaku,authorComments,authorDanmaku;
        final Set<String> audience=new HashSet<>();
        void accept(Unit u){
            if(u.source()==Source.COMMENT){comments++;if(u.author())authorComments++;}
            else {danmaku++;if(u.author())authorDanmaku++;}
            if(!u.author() && u.userId()!=null)audience.add(u.userId());
        }
        Counts counts(){return new Counts(comments,danmaku,authorComments,authorDanmaku,audience.size());}
    }
}
