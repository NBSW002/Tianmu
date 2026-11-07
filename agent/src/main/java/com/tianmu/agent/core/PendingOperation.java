package com.tianmu.agent.core;

import com.tianmu.agent.core.AgentTypes.ToolExchange;
import java.time.Instant;
import java.util.*;

/** A durable external operation, not a terminal answer or an instruction to poll with a model. */
public record PendingOperation(UUID id,String kind,Instant deadline) {
    public PendingOperation {
        Objects.requireNonNull(id);Objects.requireNonNull(deadline);
        if(kind==null || !kind.matches("[a-z][a-z0-9.-]{0,63}"))throw new IllegalArgumentException("Invalid operation kind");
    }
    /** Stored timestamps may be rounded; a child operation may never outlive its run. */
    public static PendingOperation bounded(UUID id,String kind,Instant operationDeadline,Instant runDeadline){
        Objects.requireNonNull(operationDeadline);Objects.requireNonNull(runDeadline);
        return new PendingOperation(id,kind,operationDeadline.isBefore(runDeadline)?operationDeadline:runDeadline);
    }
    public record Budget(long limit,long reservation,long charged,long reported,long unknown,boolean exhausted) {}
    public record Continuation(PendingOperation operation,Set<String> skills,List<ToolExchange> exchanges,MemoryTypes.View memory,Budget budget,String traceParent, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String policyHash) {
    public Continuation(PendingOperation operation,Set<String> skills,List<ToolExchange> exchanges,MemoryTypes.View memory,Budget budget,String traceParent) { this(operation,skills,exchanges,memory,budget,traceParent,null); }

        public Continuation(PendingOperation operation,Set<String> skills,List<ToolExchange> exchanges,MemoryTypes.View memory,Budget budget){this(operation,skills,exchanges,memory,budget,null);}
        public Continuation {skills=Set.copyOf(skills);exchanges=List.copyOf(exchanges);if(exchanges.size()>64 || traceParent!=null && !traceParent.matches("00-[a-f0-9]{32}-[a-f0-9]{16}-[a-f0-9]{2}"))throw new IllegalArgumentException("Invalid durable continuation");}
    }
}
