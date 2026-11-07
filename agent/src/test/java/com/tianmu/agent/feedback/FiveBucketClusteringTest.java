package com.tianmu.agent.feedback;

import com.tianmu.agent.runtime.control.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FiveBucketClusteringTest {
    CallControl control(){return new CallControl(Instant.now().plusSeconds(10),new CancellationToken());}
    @Test void fiveClearlySeparatedTopicsAreFullyPartitionedAndStable(){
        var rows=new ArrayList<float[]>();for(int k=0;k<5;k++)for(int i=0;i<10;i++){float[] v=new float[5];v[k]=1;rows.add(v);}
        var result=FiveBucketClustering.cluster(rows,5,50,.8,2,64,control());
        assertEquals(5,result.buckets().size());assertEquals(result,FiveBucketClustering.cluster(rows,5,50,.8,2,64,control()));
        assertTrue(result.unclassified().isEmpty());assertEquals(50,result.buckets().stream().mapToInt(List::size).sum());
        for(var bucket:result.buckets()){assertEquals(10,bucket.size());assertEquals(1,bucket.stream().map(i->i/10).distinct().count());}
    }
    @Test void identicalOrEmptyInputsNeverInventExtraBuckets(){
        assertTrue(FiveBucketClustering.cluster(List.of(),5,50,.8,2,64,control()).buckets().isEmpty());
        var same=FiveBucketClustering.cluster(List.of(new float[]{1,0},new float[]{1,0},new float[]{1,0},new float[]{0,1}),5,50,.8,2,64,control());
        assertEquals(List.of(List.of(0,1,2)),same.buckets());assertEquals(List.of(3),same.unclassified());
    }
    @Test void magnitudeDoesNotChangeCosineAndInvalidVectorsFail(){
        var result=FiveBucketClustering.cluster(List.of(new float[]{2,0},new float[]{1,0},new float[]{0,1}),2,50,.8,2,64,control());
        assertEquals(List.of(List.of(0,1)),result.buckets());assertEquals(List.of(2),result.unclassified());
        assertThrows(com.tianmu.agent.core.AgentException.class,()->FiveBucketClustering.cluster(List.of(new float[]{Float.NaN,0}),5,50,.8,2,64,control()));
        assertThrows(com.tianmu.agent.core.AgentException.class,()->FiveBucketClustering.cluster(List.of(new float[]{0,0}),5,50,.8,2,64,control()));
        var tiny=FiveBucketClustering.cluster(List.of(new float[]{Float.MIN_VALUE,0},new float[]{1,0}),2,50,.8,2,64,control());
        assertEquals(List.of(List.of(0,1)),tiny.buckets());
    }
    @Test void sparseInputKeepsIsolatedFeedbackUnclassifiedWithoutInventingFiveBuckets(){
        var single=FiveBucketClustering.cluster(List.of(new float[]{1,0}),5,50,.8,2,64,control());
        assertTrue(single.buckets().isEmpty());assertEquals(List.of(0),single.unclassified());
        var pair=FiveBucketClustering.cluster(List.of(new float[]{1,0},new float[]{1,0}),5,50,.8,2,64,control());
        assertEquals(List.of(List.of(0,1)),pair.buckets());assertTrue(pair.unclassified().isEmpty());
        var different=FiveBucketClustering.cluster(List.of(new float[]{1,0},new float[]{0,1}),5,50,.8,2,64,control());
        assertTrue(different.buckets().isEmpty());assertEquals(List.of(0,1),different.unclassified());
    }
    @Test void anIsolatedOutlierDoesNotConsumeAWellSupportedSecondBucket(){
        var rows=new ArrayList<float[]>();for(int i=0;i<10;i++)rows.add(new float[]{1,0});for(int i=0;i<10;i++)rows.add(new float[]{.5f,.8660254f});rows.add(new float[]{-1,0});
        var result=FiveBucketClustering.cluster(rows,2,50,.9,2,64,control());
        assertEquals(2,result.buckets().size());assertEquals(List.of(20),result.unclassified());
    }
    @Test void isolatedMiddleCannotConsumeTheFirstCenter(){
        var rows=new ArrayList<float[]>();for(int i=0;i<10;i++)rows.add(new float[]{.5f,-.8660254f});for(int i=0;i<10;i++)rows.add(new float[]{.5f,.8660254f});rows.add(new float[]{1,0});
        var result=FiveBucketClustering.cluster(rows,2,50,.9,2,64,control());
        assertEquals(2,result.buckets().size());assertEquals(List.of(20),result.unclassified());
    }
    @Test void candidatesAreResampledFromRemainingUncoveredMembers(){
        var rows=new ArrayList<float[]>();for(int i=0;i<129;i++)rows.add(i==1 || i==3?new float[]{0,1}:new float[]{1,0});
        var result=FiveBucketClustering.cluster(rows,2,50,.9,2,64,control());
        assertEquals(2,result.buckets().size());assertTrue(result.unclassified().isEmpty());
    }
    @Test void everyReturnedMemberPassesAgainstItsActualFinalCentroid(){
        int[] angles={-38,-135,-91,-30,-54,-39,91,-123,-70};var rows=new ArrayList<float[]>();
        for(int angle:angles){double radians=Math.toRadians(angle);rows.add(new float[]{(float)Math.cos(radians),(float)Math.sin(radians)});}
        var result=FiveBucketClustering.cluster(rows,2,1,.8,1,64,control());
        for(var bucket:result.buckets()){
            double x=0,y=0;for(int i:bucket){x+=rows.get(i)[0];y+=rows.get(i)[1];}double norm=Math.hypot(x,y);
            assertTrue(norm>0);for(int i:bucket)assertTrue((x*rows.get(i)[0]+y*rows.get(i)[1])/norm>=.8-1e-6);
        }
        assertEquals(rows.size(),result.unclassified().size()+result.buckets().stream().mapToInt(List::size).sum());
    }
}
