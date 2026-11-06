package com.tianmu.agent.runtime.control;

import java.time.Instant;

/** Do not serialize into prompts. Adapters should apply this deadline and register request cancellation. */
public record CallControl(Instant deadline, CancellationToken cancellation, ModelBudget budget, Runnable externalCallStarted, java.util.function.Consumer<String> textOutput,Runnable dispatchBarrier) {
    public CallControl(Instant deadline,CancellationToken cancellation,ModelBudget budget,Runnable started,java.util.function.Consumer<String> sink){this(deadline,cancellation,budget,started,sink,()->{});}
    public CallControl(Instant deadline,CancellationToken cancellation,ModelBudget budget,Runnable started){this(deadline,cancellation,budget,started,null);}
    public CallControl withTextOutput(java.util.function.Consumer<String> sink){return new CallControl(deadline,cancellation,budget,externalCallStarted,sink,dispatchBarrier);}
    public CallControl withDispatchBarrier(Runnable barrier){return new CallControl(deadline,cancellation,budget,externalCallStarted,textOutput,barrier);}
    public void beforeDispatch(){checkpoint();dispatchBarrier.run();checkpoint();}
    public CallControl {
        java.util.Objects.requireNonNull(deadline); java.util.Objects.requireNonNull(cancellation);
        java.util.Objects.requireNonNull(budget); java.util.Objects.requireNonNull(externalCallStarted);
        java.util.Objects.requireNonNull(dispatchBarrier);
    }
    /** Explicit standalone execution, used outside a managed run. */
    public CallControl(Instant deadline, CancellationToken cancellation) {
        this(deadline, cancellation, new ModelBudget(0, 8192), () -> {});
    }
    public CallControl withBudget(ModelBudget value) { return new CallControl(deadline, cancellation, value, externalCallStarted,textOutput,dispatchBarrier); }
    public void checkpoint() { cancellation.checkpoint(); }
}
