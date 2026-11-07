package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;

/** Optional task-local centering; shared cached embedding vectors are never mutated. */
public final class FeedbackVectorTransform {
    private FeedbackVectorTransform() {}
    public record Result(String mode,String fallback,double threshold,List<float[]> vectors){
        public Result {vectors=Collections.unmodifiableList(new ArrayList<>(vectors));}
    }
    public static Result apply(List<float[]> input,FeedbackBucketProperties policy,CallControl control){
        check(control);
        if(!policy.centeringEnabled())return raw(input,policy,"DISABLED");
        if(input.size()<policy.centerMinExpressions())return raw(input,policy,"TOO_FEW_EXPRESSIONS");
        if(input.get(0)==null || input.get(0).length==0)throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid vector dimensions");
        int dimension=input.get(0).length;double[] mean=new double[dimension];int seen=0;
        for(var vector:input){if(seen++%64==0)check(control);if(vector==null || vector.length!=dimension)throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid vector dimensions");for(int d=0;d<dimension;d++){if(!Float.isFinite(vector[d]))throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid vector value");mean[d]+=vector[d];}}
        for(int d=0;d<dimension;d++)mean[d]/=input.size();
        double[] norms=new double[input.size()];double squaredTotal=0;
        for(int i=0;i<input.size();i++){if(i%64==0)check(control);double squared=0;for(int d=0;d<dimension;d++){double v=input.get(i)[d]-mean[d];squared+=v*v;}if(!Double.isFinite(squared))throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid centered vector");norms[i]=Math.sqrt(squared);squaredTotal+=squared;}
        if(Math.sqrt(squaredTotal/input.size())<policy.centerMinRms())return raw(input,policy,"LOW_VARIANCE");
        var out=new ArrayList<float[]>(input.size());int usable=0;
        for(int i=0;i<input.size();i++){if(i%64==0)check(control);if(norms[i]<policy.centerMinResidual()){out.add(null);continue;}float[] vector=new float[dimension];for(int d=0;d<dimension;d++)vector[d]=(float)((input.get(i)[d]-mean[d])/norms[i]);out.add(vector);usable++;}
        if(usable<policy.minMembers())return raw(input,policy,"TOO_FEW_RESIDUALS");
        if((input.size()-usable)/(double)input.size()>policy.centerMaxExcludedFraction())return raw(input,policy,"TOO_MANY_NEAR_MEAN");
        return new Result("TASK_CENTERED_COSINE","NONE",policy.centeredSimilarity(),out);
    }
    private static void check(CallControl control){control.checkpoint();if(!java.time.Instant.now().isBefore(control.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Vector transform deadline exceeded");}
    private static Result raw(List<float[]> input,FeedbackBucketProperties policy,String reason){return new Result("RAW_COSINE",reason,policy.minSimilarity(),input);}
}
