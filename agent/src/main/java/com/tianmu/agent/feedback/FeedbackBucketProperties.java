package com.tianmu.agent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Task-frozen vector bucket policy. Thresholds need domain calibration. */
@ConfigurationProperties("agent.feedback.buckets")
public record FeedbackBucketProperties(@DefaultValue("5") int maxBuckets,
        @DefaultValue("0.65") double minSimilarity,@DefaultValue("2") int minMembers,
        @DefaultValue("50") int maxIterations,@DefaultValue("64") int candidateSamples,
        @DefaultValue("false") boolean centeringEnabled,@DefaultValue("20") int centerMinExpressions,
        @DefaultValue("0.01") double centerMinRms,@DefaultValue("0.001") double centerMinResidual,
        @DefaultValue("0.30") double centeredSimilarity,@DefaultValue("0.20") double centerMaxExcludedFraction) {
    public FeedbackBucketProperties(int maxBuckets,double minSimilarity,int minMembers,int maxIterations,int candidateSamples){this(maxBuckets,minSimilarity,minMembers,maxIterations,candidateSamples,false,20,.01,.001,.30,.20);}
    public FeedbackBucketProperties(int maxBuckets,double minSimilarity,int minMembers,int maxIterations,int candidateSamples,boolean centeringEnabled,int centerMinExpressions,double centerMinRms,double centerMinResidual,double centeredSimilarity){this(maxBuckets,minSimilarity,minMembers,maxIterations,candidateSamples,centeringEnabled,centerMinExpressions,centerMinRms,centerMinResidual,centeredSimilarity,.20);}
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public FeedbackBucketProperties {
        if(maxBuckets<1 || maxBuckets>5 || !Double.isFinite(minSimilarity) || minSimilarity<0 || minSimilarity>1
                || minMembers<2 || minMembers>100 || maxIterations<1 || maxIterations>100 || candidateSamples<1 || candidateSamples>256
                || centerMinExpressions<5 || centerMinExpressions>5000 || !Double.isFinite(centerMinRms) || centerMinRms<=0 || centerMinRms>1
                || !Double.isFinite(centerMinResidual) || centerMinResidual<=0 || centerMinResidual>centerMinRms
                || !Double.isFinite(centeredSimilarity) || centeredSimilarity<0 || centeredSimilarity>1
                || !Double.isFinite(centerMaxExcludedFraction) || centerMaxExcludedFraction<0 || centerMaxExcludedFraction>1)
            throw new IllegalArgumentException("Invalid feedback bucket policy");
    }
    public String revision(){return "feedback-buckets-v2:"+FeedbackObjects.sha(toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
