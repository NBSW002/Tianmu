package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.spi.CompletionEvaluator;
import org.springframework.stereotype.Component;
import java.util.*;

/** A queued operation is not a persisted-memory receipt. */
@Component
public final class MemoryReceiptRequirement implements CompletionRequirement {
    public String name(){return "memory-write-receipt";}
    public void verify(CompletionEvaluator.Input input,CompletionEvaluator.Deliver proposal){
        String text=proposal.draft().text();
        boolean claims=Arrays.stream(text.split("[。！!\\n]" )).map(String::strip).anyMatch(line->line.matches("^(?:好的[，, ]*)?(?:(?:我|已经|已)[，, ]*)?(?:已经|已)?(?:记住了|记下了|记住|保存到(?:长期|个人)?记忆|保存了).*"));
        if(!claims)return;
        boolean saved=input.exchanges().stream().map(e->e.result()).anyMatch(r->r.tool().equals("remember_memory")
            && r.data().containsKey("id") && r.data().get("version") instanceof Number
            && Set.of("CREATED","UPDATED").contains(Objects.toString(r.data().get("action"),"")));
        if(!saved)throw new AgentException("MEMORY_RECEIPT_REQUIRED","Cannot claim saved memory without a committed receipt");
    }
}
