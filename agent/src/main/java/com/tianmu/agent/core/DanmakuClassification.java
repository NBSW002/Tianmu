package com.tianmu.agent.core;

import com.tianmu.agent.core.AudienceData.Topic;
import java.util.List;

/** Read-only format for historical persisted report checkpoints. New runs never write these checkpoints. */
public record DanmakuClassification(int offset, List<Topic> topics, String videoEvidenceId, boolean merged) {
    public DanmakuClassification { if(offset<0)throw new IllegalArgumentException();topics=List.copyOf(topics); }
}
