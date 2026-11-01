package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianmu.agent.runtime.control.ModelBudget;
import java.util.Map;

/** One provider attempt per fenced work claim. Unknown usage remains durably charged. */
public final class FeedbackBudget extends ModelBudget {
    private final FeedbackJobStore store;private final FeedbackJobStore.Work work;
    public FeedbackBudget(FeedbackJobStore store,FeedbackJobStore.Work work){super(0,8192);this.store=store;this.work=work;}
    @Override protected synchronized long reserve(long estimate,Runnable beforeDispatch){
        long reserved=store.reserve(work.jobId(),work.id(),work.fence(),Math.max(8192,estimate));
        if(beforeDispatch!=null)beforeDispatch.run();return reserved;
    }
    @Override protected synchronized void settle(JsonNode response,long reserved){
        var in=response.path("usage").path("prompt_tokens");var out=response.path("usage").path("completion_tokens");
        if(in.isIntegralNumber() && in.canConvertToLong() && in.longValue()>=0 && out.isIntegralNumber() && out.canConvertToLong() && out.longValue()>=0 && in.longValue()<=Long.MAX_VALUE-out.longValue()){
            store.settle(work.jobId(),work.id(),work.fence(),in.longValue(),out.longValue());
            var job=store.get(work.jobId());if(job.chargedTokens()>job.tokenLimit())throw new com.tianmu.agent.core.AgentException("MODEL_TOKEN_BUDGET_EXCEEDED","Reported usage exceeded analysis budget");
        }
    }
    @Override public synchronized Map<String,Object> attributes(){var j=store.get(work.jobId());return Map.of("budget.token_limit",j.tokenLimit(),"budget.charged_tokens",j.chargedTokens(),"analysis.model_calls",j.modelCalls());}
}
