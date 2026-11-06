package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.tool.ToolDispatcher;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.*;
import java.util.concurrent.*;

/** Native bounded dispatch + ordered settlement. Reference: DeepSeek tools/batch.ts fcc0ad462172.
 * No handler owns the parent exchange list. Actual workers retain capacity until IO has stopped. */
@Component
public final class ToolBatchExecutor {
    public record Result(ToolCall call,ToolDispatcher.Branch branch,ToolResult result,boolean success){}
    @Autowired(required=false) private ToolBatchLedger ledger;
    private final RuntimeHarness harness;private final ThreadPoolTaskExecutor workers;private final boolean enabled;private final int parallelism;
    public ToolBatchExecutor(RuntimeHarness harness,@Qualifier("toolBatchWorkers") ThreadPoolTaskExecutor workers,
            @Value("${agent.tools.parallel.enabled:false}")boolean enabled,@Value("${agent.tools.parallel.per-run:2}")int parallelism){
        if(parallelism<1 || parallelism>4)throw new IllegalArgumentException("Invalid tool concurrency");
        this.harness=harness;this.workers=workers;this.enabled=enabled;this.parallelism=parallelism;
    }
    public boolean accepts(List<ToolCall> calls,RunState run,ToolDispatcher tools){return enabled && parallelism>1 && calls.stream().allMatch(c->tools.parallelSafe(c,run));}
    public List<Result> execute(RunState run,List<ToolCall> calls,ToolDispatcher tools){
        var branches=calls.stream().map(c->tools.branch(c,run)).toList();
        var done=new LinkedBlockingQueue<Integer>();var futures=new ArrayList<FutureTask<Result>>();var submitted=new ArrayList<Runnable>();
        var results=new Result[calls.size()];int next=0,active=0,completed=0;
        try{
            while(completed<calls.size()){
                run.checkpoint();
                while(next<calls.size() && active<parallelism){
                    int index=next++;var call=calls.get(index);var branch=branches.get(index);
                    var task=new FutureTask<Result>(()->harness.stage(run,"agent.tool",null,null,
                            Map.of("tool.name",call.name(),"tool.batch_index",index,"tool.execution_mode","PARALLEL","tool.call_id",call.providerCallId()==null?"batch-"+index:call.providerCallId()),()->{
                        run.checkpoint();TraceDetails.input(call);
                        try{
                            var cached=ledger==null?null:ledger.replay(run,index);
                            var value=cached==null?branch.execute():cached;branch.validate(value);
                            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("tool.success",true,"tool.has_evidence",value.evidenceId()!=null,"tool.replay",cached!=null));
                            TraceDetails.output(value);return new Result(call,branch,value,true);
                        }catch(AgentException e){
                            if(fatal(e.code()))throw e;
                            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("tool.success",false,"tool.error_code",e.code()));
                            return new Result(call,branch,new ToolResult(call.name(),Map.of("error_code",e.code(),"retryable",Set.of("TOOL_CALL_TIMEOUT","TOOL_TEMPORARY_ERROR").contains(e.code())),null),false);
                        }
                    }));
                    Runnable wrapped=harness.contextual(()->{try{task.run();}finally{done.offer(index);}});
                    futures.add(task);submitted.add(wrapped);
                    try{workers.execute(wrapped);}catch(RejectedExecutionException e){task.cancel(false);throw new AgentException("CALL_CAPACITY_EXCEEDED","Tool batch workers are full");}
                    active++;
                }
                Integer index=done.poll(100,TimeUnit.MILLISECONDS);if(index==null)continue;
                try{results[index]=futures.get(index).get();}
                catch(ExecutionException e){if(e.getCause() instanceof AgentException a)throw a;throw new AgentException("TOOL_ERROR","Isolated tool failed");}
                active--;completed++;
            }
            run.checkpoint();return List.of(results);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AgentException("CANCELLED","Tool batch interrupted");}
        finally{
            for(int i=0;i<futures.size();i++)if(!futures.get(i).isDone()){futures.get(i).cancel(true);workers.getThreadPoolExecutor().remove(submitted.get(i));}
        }
    }
    private static boolean fatal(String code){return Set.of("FORBIDDEN","VIDEO_NOT_FOUND","CANCELLED","RUN_TERMINATED","DEADLINE_EXCEEDED","MEMORY_CONTEXT_INVALIDATED","MEMORY_SOURCE_UNAVAILABLE","MODEL_CALL_BUDGET_EXCEEDED","TOKEN_BUDGET_EXCEEDED","TASK_CONSTRAINT_VIOLATION").contains(code);}
}
