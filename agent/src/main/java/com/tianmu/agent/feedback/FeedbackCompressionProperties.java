package com.tianmu.agent.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Frozen per-material policy. Names distinguish record, byte and character budgets. */
@ConfigurationProperties("agent.feedback.compression")
public record FeedbackCompressionProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("3") int minEffectiveChars,@DefaultValue("15000") int playbackBucketMs,
        @DefaultValue("0.90") double minSimilarity,@DefaultValue("32") int neighbors,
        @DefaultValue("0.75") double minCentrality,@DefaultValue("3") int representativesPerCluster,
        @DefaultValue("3") int maxPerCluster,@DefaultValue("60") int maxRepresentatives,
        @DefaultValue("0.96") double nearDuplicateSimilarity,@DefaultValue("300") int textChars,
        @DefaultValue("600") int contextChars,@DefaultValue("32768") int packageBytes,
        @DefaultValue("67108864") int vectorBytes,@DefaultValue("512") int embeddingCalls,@DefaultValue("12000") int batchBytes,@DefaultValue("15000") int momentMatchMaxGapMs) {
    public FeedbackCompressionProperties(boolean enabled,int minEffectiveChars,int playbackBucketMs,double minSimilarity,int neighbors,double minCentrality,int representativesPerCluster,int maxPerCluster,int maxRepresentatives,double nearDuplicateSimilarity,int textChars,int contextChars,int packageBytes,int vectorBytes,int embeddingCalls){
        this(enabled,minEffectiveChars,playbackBucketMs,minSimilarity,neighbors,minCentrality,representativesPerCluster,maxPerCluster,maxRepresentatives,nearDuplicateSimilarity,textChars,contextChars,packageBytes,vectorBytes,embeddingCalls,12000,15000);
    }
    public FeedbackCompressionProperties(boolean enabled,int minEffectiveChars,int playbackBucketMs,double minSimilarity,int neighbors,double minCentrality,int representativesPerCluster,int maxPerCluster,int maxRepresentatives,double nearDuplicateSimilarity,int textChars,int contextChars,int packageBytes,int vectorBytes,int embeddingCalls,int batchBytes){this(enabled,minEffectiveChars,playbackBucketMs,minSimilarity,neighbors,minCentrality,representativesPerCluster,maxPerCluster,maxRepresentatives,nearDuplicateSimilarity,textChars,contextChars,packageBytes,vectorBytes,embeddingCalls,batchBytes,15000);}
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public FeedbackCompressionProperties {
        if(minEffectiveChars<0 || minEffectiveChars>20 || playbackBucketMs<1000 || playbackBucketMs>120000
                || !Double.isFinite(minSimilarity) || minSimilarity<=0 || minSimilarity>1 || neighbors<1 || neighbors>256
                || !Double.isFinite(minCentrality) || minCentrality< -1 || minCentrality>1
                || representativesPerCluster<2 || representativesPerCluster>5 || maxPerCluster<representativesPerCluster || maxPerCluster>10
                || maxRepresentatives<1 || maxRepresentatives>500 || !Double.isFinite(nearDuplicateSimilarity)
                || nearDuplicateSimilarity<minSimilarity || nearDuplicateSimilarity>1 || textChars<50 || textChars>2000
                || contextChars<0 || contextChars>2000 || packageBytes<4096 || packageBytes>131072
                || vectorBytes<1048576 || vectorBytes>268435456 || embeddingCalls<1 || embeddingCalls>2048 || batchBytes<1024 || batchBytes>24000 || momentMatchMaxGapMs<0 || momentMatchMaxGapMs>120000)
            throw new IllegalArgumentException("Invalid feedback compression policy");
    }
    public String revision(){return "representatives-v1:"+FeedbackObjects.sha(toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public static FeedbackCompressionProperties defaults(){return new FeedbackCompressionProperties(false,3,15000,.90,32,.75,3,3,60,.96,300,600,32768,67108864,512);}
}
