package com.tianmu.agent.runtime.spi;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PendingOperation;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.core.TaskTypes.PreparedTask;
import java.util.*;

/** Pure whole-ledger decision. An eligible proposal still passes the transactional delivery boundary. */
public interface CompletionEvaluator {
    record Input(PreparedTask task,List<ToolExchange> exchanges,Set<String> registeredArtifacts,
                 Answer answerProposal,String clarificationProposal,StopReason proposalReason) {
        public Input(PreparedTask task,List<ToolExchange> exchanges,Set<String> registeredArtifacts){this(task,exchanges,registeredArtifacts,null,null,StopReason.ANSWER);}
        public Input { exchanges=List.copyOf(exchanges);registeredArtifacts=Set.copyOf(registeredArtifacts); }
    }
    sealed interface Decision permits Continue,Await,Deliver,Clarify,Reject {}
    record Continue(String reason) implements Decision {}
    record Await(PendingOperation operation) implements Decision {}
    record Deliver(Answer draft,StopReason reason) implements Decision {}
    record Clarify(String question) implements Decision {}
    record Reject(String code) implements Decision {}
    Decision evaluate(Input input);
}
