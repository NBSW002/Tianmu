package com.tianmu.agent.feedback;

import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.core.AgentException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackVectorTransformTest {
    CallControl control(){return new CallControl(Instant.now().plusSeconds(10),new CancellationToken());}
    FeedbackBucketProperties enabled(){return new FeedbackBucketProperties(5,.65,2,50,64,true,20,.01,.001,.30);}
    @Test void fewOrHomogeneousExpressionsUseTheOriginalSpaceAndThreshold(){
        assertEquals("TOO_FEW_EXPRESSIONS",FeedbackVectorTransform.apply(List.of(),enabled(),control()).fallback());
        assertEquals("TOO_FEW_EXPRESSIONS",FeedbackVectorTransform.apply(List.of(new float[]{1,0}),enabled(),control()).fallback());
        var few=FeedbackVectorTransform.apply(List.of(new float[]{1,0},new float[]{0,1}),enabled(),control());
        assertEquals("RAW_COSINE",few.mode());assertEquals("TOO_FEW_EXPRESSIONS",few.fallback());assertEquals(.65,few.threshold());
        var same=new ArrayList<float[]>();for(int i=0;i<20;i++)same.add(new float[]{1,0});
        var homogeneous=FeedbackVectorTransform.apply(same,enabled(),control());assertEquals("LOW_VARIANCE",homogeneous.fallback());assertEquals(.65,homogeneous.threshold());
    }
    @Test void invalidVectorsAndDeadlineFailInsteadOfProducingMisleadingBuckets(){
        var input=new ArrayList<float[]>();for(int i=0;i<20;i++)input.add(new float[]{1,0});
        input.set(0,null);assertEquals("EMBEDDING_PROTOCOL_ERROR",assertThrows(AgentException.class,()->FeedbackVectorTransform.apply(input,enabled(),control())).code());
        input.set(0,new float[]{Float.NaN,0});assertEquals("EMBEDDING_PROTOCOL_ERROR",assertThrows(AgentException.class,()->FeedbackVectorTransform.apply(input,enabled(),control())).code());
        var expired=new CallControl(Instant.now().minusSeconds(1),new CancellationToken());
        assertEquals("DEADLINE_EXCEEDED",assertThrows(AgentException.class,()->FeedbackVectorTransform.apply(List.of(),enabled(),expired)).code());
    }
    @Test void centeredSpaceIsTaskLocalAndNearMeanResidualsAreNotMixedWithRawVectors(){
        var input=new ArrayList<float[]>();for(int i=0;i<18;i++)input.add(new float[]{1,0});input.add(new float[]{.9987492f,.05f});input.add(new float[]{.9987492f,-.05f});
        var result=FeedbackVectorTransform.apply(input,enabled(),control());assertEquals("RAW_COSINE",result.mode());assertEquals("TOO_MANY_NEAR_MEAN",result.fallback());assertEquals(.65,result.threshold());
        assertEquals(0,result.vectors().stream().filter(Objects::isNull).count());assertArrayEquals(new float[]{1,0},input.get(0));
        var balanced=new ArrayList<float[]>();for(int i=0;i<10;i++){balanced.add(new float[]{.9987492f,.05f});balanced.add(new float[]{.9987492f,-.05f});}balanced.add(new float[]{.9987492f,0});
        var centered=FeedbackVectorTransform.apply(balanced,enabled(),control());assertEquals("TASK_CENTERED_COSINE",centered.mode());assertEquals(.30,centered.threshold());assertEquals(1,centered.vectors().stream().filter(Objects::isNull).count());
    }
}
