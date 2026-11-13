package com.orangecode.tianmu.common;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** Process-wide, low-cardinality counters; logs contain no credentials or message bodies. */
@Component
public class DanmakuMetrics {
    public enum Stage { AUTH, VIDEO_CHECK, MQ_SEND, DB_BATCH_BODY }
    private static final java.util.concurrent.atomic.AtomicLongArray counts=new java.util.concurrent.atomic.AtomicLongArray(Stage.values().length);
    private static final java.util.concurrent.atomic.AtomicLongArray nanos=new java.util.concurrent.atomic.AtomicLongArray(Stage.values().length);
    public static void observe(Stage stage,long elapsedNanos) { counts.incrementAndGet(stage.ordinal());nanos.addAndGet(stage.ordinal(),elapsedNanos); }
    public static final LongAdder rejected=new LongAdder(), writeFailures=new LongAdder(), nonWritable=new LongAdder();
    public static final LongAdder frames=new LongAdder(), recipients=new LongAdder(), onlineFrames=new LongAdder();
    public static final AtomicLong pendingTasks=new AtomicLong();
    public static Map<String,Long> snapshot() {
        var result=new java.util.LinkedHashMap<>(Map.of("rejected",rejected.sum(),"writeFailures",writeFailures.sum(),"nonWritableObservations",nonWritable.sum(),
            "broadcastFrames",frames.sum(),"recipientAttempts",recipients.sum(),"onlineBroadcasts",onlineFrames.sum(),"pendingTasks",pendingTasks.get()));
        for(var stage:Stage.values()){result.put(stage.name()+"_count",counts.get(stage.ordinal()));result.put(stage.name()+"_nanos",nanos.get(stage.ordinal()));}
        return result;
    }
    @Scheduled(fixedDelay=30000)
    public void report() { org.slf4j.LoggerFactory.getLogger(getClass()).info("danmaku_metrics {}",snapshot()); }
}
