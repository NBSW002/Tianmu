package com.tianmu.agent.core;

import com.tianmu.agent.core.AgentTypes.ToolExchange;
import java.time.Instant;
import java.util.*;

/** A durable external operation, not a terminal answer or an instruction to poll with a model. */
public record PendingOperation(UUID id,String kind,Instant deadline) {
    public PendingOperation {
        Objects.requireNonNull(id);Objects.requireNonNull(deadline);
        if(!Set.of("feedback-analysis","feedback-sources","feedback-report").contains(kind))throw new IllegalArgumentException("Unsupported operation kind");
    }
    public record Budget(long limit,long reservation,long charged,long reported,long unknown,boolean exhausted) {}
    public record Continuation(PendingOperation operation,Set<String> skills,List<ToolExchange> exchanges,MemoryTypes.View memory,Budget budget,String traceParent) {
        public Continuation(PendingOperation operation,Set<String> skills,List<ToolExchange> exchanges,MemoryTypes.View memory,Budget budget){this(operation,skills,exchanges,memory,budget,null);}
        public Continuation {skills=Set.copyOf(skills);exchanges=List.copyOf(exchanges);if(exchanges.size()>64 || traceParent!=null && !traceParent.matches("00-[a-f0-9]{32}-[a-f0-9]{16}-[a-f0-9]{2}"))throw new IllegalArgumentException("Invalid durable continuation");}
    }
}
