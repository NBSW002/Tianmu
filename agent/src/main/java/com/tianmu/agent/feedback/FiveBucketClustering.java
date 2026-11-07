package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;

/** Deterministic spherical k-means over the authorized members of one task. */
public final class FiveBucketClustering {
    private FiveBucketClustering() {}
    public record Result(List<List<Integer>> buckets,List<Integer> unclassified) {
        public Result {buckets=List.copyOf(buckets);unclassified=List.copyOf(unclassified);}
    }
    public static Result cluster(List<float[]> vectors,int limit,int iterations,double minSimilarity,int minMembers,int candidateSamples,CallControl control) {
        if(limit<1 || limit>5 || iterations<1 || iterations>100 || !Double.isFinite(minSimilarity) || minSimilarity< -1 || minSimilarity>1 || minMembers<1 || candidateSamples<1 || candidateSamples>256)throw new IllegalArgumentException("Invalid clustering limits");
        if(vectors.isEmpty())return new Result(List.of(),List.of());
        if(vectors.get(0)==null)throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Null vector");
        int dimension=vectors.get(0).length;if(dimension==0)throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Empty vector");
        var normalized=new ArrayList<float[]>(vectors.size());
        int normalizedCount=0;for(var v:vectors){
            if(normalizedCount++%64==0)check(control);
            if(v==null || v.length!=dimension)throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Mismatched vector dimensions");
            double squared=0;for(float value:v){if(!Float.isFinite(value))throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid vector value");squared+=(double)value*value;}
            if(!Double.isFinite(squared) || squared==0)throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Empty vector norm");
            float[] copy=v.clone();double norm=Math.sqrt(squared);for(int d=0;d<dimension;d++)copy[d]=(float)(copy[d]/norm);normalized.add(copy);
        }
        vectors=normalized;
        // Bounded, stable candidate sampling chooses supported centers before isolated points.
        var centers=new ArrayList<float[]>();
        while(centers.size()<Math.min(limit,vectors.size())) {
            var remaining=new ArrayList<Integer>();for(int i=0;i<vectors.size();i++){if(i%64==0)check(control);boolean covered=false;for(var center:centers)if(dot(vectors.get(i),center)>=minSimilarity){covered=true;break;}if(!covered)remaining.add(i);}
            int candidate=-1,bestSupport=0;int samples=Math.min(candidateSamples,remaining.size());
            for(int sample=0;sample<samples;sample++){
                check(control);int i=remaining.get((int)((long)sample*(remaining.size()-1)/Math.max(1,samples-1)));float[] proposed=vectors.get(i);
                int support=0;for(int j=0;j<remaining.size();j++){if(j%64==0)check(control);if(dot(proposed,vectors.get(remaining.get(j)))>=minSimilarity)support++;}
                if(support>bestSupport){bestSupport=support;candidate=i;}
            }
            if(candidate<0 || bestSupport<minMembers)break;centers.add(vectors.get(candidate).clone());
        }
        if(centers.isEmpty()){var all=new ArrayList<Integer>();for(int i=0;i<vectors.size();i++)all.add(i);return new Result(List.of(),all);}
        int[] assigned=new int[vectors.size()];Arrays.fill(assigned,-1);
        for(int iteration=0;iteration<iterations;iteration++){
            check(control);boolean changed=false;
            for(int i=0;i<vectors.size();i++){if(i%64==0)check(control);int winner=0;double score=dot(vectors.get(i),centers.get(0));for(int k=1;k<centers.size();k++){double candidate=dot(vectors.get(i),centers.get(k));if(candidate>score){score=candidate;winner=k;}}if(score<minSimilarity)winner=-1;if(assigned[i]!=winner){assigned[i]=winner;changed=true;}}
            if(!changed)break;
            float[][] sums=new float[centers.size()][dimension];int[] counts=new int[centers.size()];
            for(int i=0;i<vectors.size();i++){if(i%64==0)check(control);int k=assigned[i];if(k<0)continue;counts[k]++;for(int d=0;d<dimension;d++)sums[k][d]+=vectors.get(i)[d];}
            for(int k=0;k<centers.size();k++)if(counts[k]>0){double norm=0;for(float v:sums[k])norm+=(double)v*v;if(norm>0){double divisor=Math.sqrt(norm);for(int d=0;d<dimension;d++)sums[k][d]=(float)(sums[k][d]/divisor);centers.set(k,sums[k]);}}
        }
        // Re-check against final centers even when the iteration limit is reached.
        for(int i=0;i<vectors.size();i++){if(i%64==0)check(control);int winner=0;double score=dot(vectors.get(i),centers.get(0));for(int k=1;k<centers.size();k++){double candidate=dot(vectors.get(i),centers.get(k));if(candidate>score){score=candidate;winner=k;}}assigned[i]=score>=minSimilarity?winner:-1;}
        var result=new ArrayList<List<Integer>>();var unclassified=new ArrayList<Integer>();for(int i=0;i<assigned.length;i++)if(assigned[i]<0)unclassified.add(i);
        for(int k=0;k<centers.size();k++){
            check(control);var members=new ArrayList<Integer>();for(int i=0;i<assigned.length;i++){if(i%64==0)check(control);if(assigned[i]==k)members.add(i);}
            // Removing weak members changes the centroid, so repeat until all retained members pass.
            boolean stable=false;for(int pass=0;pass<iterations && members.size()>=minMembers;pass++){
                check(control);float[] center=new float[dimension];int cursor=0;
                for(int i:members){if(cursor++%64==0)check(control);for(int d=0;d<dimension;d++)center[d]+=vectors.get(i)[d];}
                double squared=0;for(float value:center)squared+=(double)value*value;if(squared==0){unclassified.addAll(members);members.clear();break;}
                double divisor=Math.sqrt(squared);for(int d=0;d<dimension;d++)center[d]=(float)(center[d]/divisor);
                var kept=new ArrayList<Integer>();for(int i:members){if(dot(vectors.get(i),center)>=minSimilarity)kept.add(i);else unclassified.add(i);}
                if(kept.size()==members.size()){stable=true;break;}members=kept;
            }
            if(stable && members.size()>=minMembers)result.add(List.copyOf(members));else unclassified.addAll(members);
        }
        Collections.sort(unclassified);return new Result(result,unclassified);
    }
    private static double dot(float[] a,float[] b){double result=0;for(int i=0;i<a.length;i++)result+=(double)a[i]*b[i];return result;}
    private static void check(CallControl c){c.checkpoint();if(!java.time.Instant.now().isBefore(c.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Bucket clustering deadline exceeded");}
}
