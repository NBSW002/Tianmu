package com.tianmu.agent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Task-frozen vector bucket policy. Thresholds need domain calibration. */
@ConfigurationProperties("agent.feedback.buckets")
public record FeedbackBucketProperties(@DefaultValue("5") int maxBuckets,
        @DefaultValue("0.65") double minSimilarity,@DefaultValue("2") int minMembers,
        @DefaultValue("50") int maxIterations,@DefaultValue("64") int candidateSamples) {
    public FeedbackBucketProperties {
        if(maxBuckets<1 || maxBuckets>5 || !Double.isFinite(minSimilarity) || minSimilarity<0 || minSimilarity>1
                || minMembers<2 || minMembers>100 || maxIterations<1 || maxIterations>100 || candidateSamples<1 || candidateSamples>256)
            throw new IllegalArgumentException("Invalid feedback bucket policy");
    }
    public String revision(){return "feedback-buckets-v2:"+FeedbackObjects.sha(toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
