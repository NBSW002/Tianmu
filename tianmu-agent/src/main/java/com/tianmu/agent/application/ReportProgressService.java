package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.Evidence;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.runtime.EvidenceContext;
import com.tianmu.agent.runtime.ExecutionState;
import java.util.*;

/** Run-scoped report state admission. Private checkpoints are authoritative; memory is disposable. */
public final class ReportProgressService {
    private final EvidenceContext context;
    private final ExecutionState execution;

    public ReportProgressService(EvidenceContext context, ExecutionState execution) {
        this.context = Objects.requireNonNull(context);
        this.execution = Objects.requireNonNull(execution);
    }

    public DanmakuClassification load(String id) {
        context.checkpoint();
        Evidence input = owned(id);
        if (!execution.hasClassification(id)) {
            var checkpoints = context.evidenceStore().snapshot().values().stream()
                    .filter(e -> e.runId().equals(context.runId()) && e.owner().equals(context.scope())
                            && e.reportCheckpoint() != null && e.reportCheckpoint().evidenceId().equals(id))
                    .map(e -> e.reportCheckpoint().classification()).toList();
            var rank = Comparator.comparingInt(DanmakuClassification::offset).thenComparing(DanmakuClassification::merged);
            var restored = checkpoints.stream().max(rank)
                    .orElse(DanmakuClassification.empty());
            if (checkpoints.stream().anyMatch(candidate -> rank.compare(candidate, restored) == 0 && !candidate.equals(restored)))
                throw invalid("Conflicting report checkpoints at the same position");
            validate(input, restored);
            execution.classification(id, restored);
        }
        return execution.classification(id);
    }

    public void save(String id, DanmakuClassification progress) {
        context.checkpoint();
        Evidence input = owned(id);
        validate(input, progress);
        var previous = load(id);
        if (progress.offset() < previous.offset() || previous.merged() && !progress.merged()
                || previous.videoEvidenceId() != null && !previous.videoEvidenceId().equals(progress.videoEvidenceId()))
            throw invalid("Report progress cannot go backwards or change its video evidence");
        if (previous.equals(progress)) return;
        if (previous.offset() == progress.offset() && previous.merged() == progress.merged())
            throw invalid("Report progress at the same position cannot be replaced");
        var checkpoint = new ReportCheckpoint("audience-report-checkpoint-v1", id, progress);
        context.checkpoint();
        context.evidenceStore().put(new Evidence("ev_" + UUID.randomUUID(), context.runId(), context.scope(), null, null, null, null, checkpoint));
        // A failed private-store write must not advance the disposable runtime cache.
        execution.classification(id, progress);
    }

    private Evidence owned(String id) {
        Evidence value = context.evidenceStore().get(id);
        if (value == null || !value.runId().equals(context.runId()) || !value.owner().equals(context.scope()))
            throw new AgentException("RESULT_NOT_FOUND", "Result is not accessible in this run");
        return value;
    }

    private void validate(Evidence input, DanmakuClassification progress) {
        if (progress == null || input.analysis() == null)
            throw invalid("Report classification requires analysis evidence");
        var data = input.analysis().audience();
        if (data == null) {
            if (!progress.equals(DanmakuClassification.empty())) throw invalid("Legacy sampled evidence has no full classification");
            return;
        }
        if (progress.offset() > data.corpus().size() || progress.merged() && progress.offset() != data.corpus().size())
            throw invalid("Report checkpoint is outside the classified prefix");
        Map<String, TextGroup> prefix = new HashMap<>();
        data.corpus().subList(0, progress.offset()).forEach(group -> prefix.put(group.id(), group));
        Set<String> covered = new HashSet<>(), topics = new HashSet<>();
        for (Topic topic : progress.topics()) {
            if (topic.id() == null || topic.title() == null || topic.observation() == null || topic.recommendation() == null
                    || topic.videoContext() == null || !topics.add(topic.id()) || topic.sampleIds().isEmpty())
                throw invalid("Invalid or duplicate topic");
            long messages = 0;
            for (String id : topic.sampleIds()) {
                TextGroup group = prefix.get(id);
                if (group == null || !covered.add(id)) throw invalid("Unknown or repeated classification member");
                messages = Math.addExact(messages, group.count());
            }
            if (topic.messages() != messages) throw invalid("Topic count differs from its original records");
        }
        if (!covered.equals(prefix.keySet())) throw invalid("Report checkpoint omits classified records");
        if (progress.videoEvidenceId() != null) {
            var video = owned(progress.videoEvidenceId()).videoContent();
            if (video == null || video.videoId() != input.analysis().query().videoId()) throw invalid("Report video differs from its input");
            new BusinessEvidencePolicy().checkVideoQuery(context, video.videoId(), video.startMs(), video.endMs());
        } else if (progress.topics().stream().anyMatch(t -> !t.videoContext().isBlank() || !t.videoAtMs().isEmpty())) {
            throw invalid("Video conclusions require owned video evidence");
        }
    }

    private AgentException invalid(String message) { return new AgentException("INVALID_REPORT", message); }
}
