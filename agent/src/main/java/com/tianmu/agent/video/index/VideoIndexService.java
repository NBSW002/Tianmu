package com.tianmu.agent.video.index;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded, resumable-by-retry indexing. Analysis cache reuses completed versioned intervals. */
@Service
public final class VideoIndexService {
    private final com.tianmu.agent.video.VideoRuntimeProperties governance;
    private record Work(AccessScope scope,long video,CancellationToken token,CallControl control){}
    private final VideoIndexStore store;private final ObjectProvider<VideoContentPort> ports;private final VideoAnalysisService analysis;
    private final ThreadPoolExecutor workers;
    private final Map<UUID,Work> active=new ConcurrentHashMap<>();
    public VideoIndexService(VideoIndexStore store,ObjectProvider<VideoContentPort> ports,VideoAnalysisService analysis) { this(store, ports, analysis, com.tianmu.agent.video.VideoRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public VideoIndexService(VideoIndexStore store,ObjectProvider<VideoContentPort> ports,VideoAnalysisService analysis, com.tianmu.agent.video.VideoRuntimeProperties governance) { this.governance=governance;this.workers=new ThreadPoolExecutor(governance.indexWorkers(),governance.indexWorkers(),0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(governance.indexQueue()),r->{var t=new Thread(r,"video-index");t.setDaemon(true);return t;});this.store=store;this.ports=ports;this.analysis=analysis;}
    private VideoContentPort port(){var port=ports.getIfAvailable();if(port==null)throw new AgentException("VIDEO_CONTENT_UNAVAILABLE","Video source unavailable");return port;}
    private CallControl control(){return new CallControl(Instant.now().plusSeconds(governance.sourceTimeoutSeconds()),new CancellationToken());}
    private void allowed(AccessScope scope,long video){if(!scope.readableVideoIds().contains(video))throw new AgentException("FORBIDDEN","Video is not readable");}
    public VideoIndex get(AccessScope scope,long video){
        allowed(scope,video);var current=store.get(scope,video);if(current==null)return null;
        // Live authorization and source version are mandatory even when a complete index is cached.
        VideoContent.Source source;
        try{source=port().source(video,scope,control());}
        catch(AgentException e){if(Set.of("FORBIDDEN","VIDEO_NOT_FOUND").contains(e.code()))invalidate(scope,current,"REVOKED");throw e;}
        if(!source.sourceVersion().equals(current.sourceVersion()) || source.durationMs()!=current.durationMs()){
            invalidate(scope,current,"STALE");return store.get(scope,video);
        }
        if(current.active() && (current.deadline().isBefore(Instant.now()) || current.updatedAt().isBefore(Instant.now().minusSeconds(governance.staleAfterSeconds())))){
            store.replace(scope,current,current.state("FAILED",List.of(),"INDEX_INTERRUPTED"));current=store.get(scope,video);
        }
        return current;
    }
    private void invalidate(AccessScope scope,VideoIndex current,String status){
        UUID generation=current.jobId();
        for(int attempt=0;attempt<8;attempt++){
            if(current==null || !current.jobId().equals(generation))throw new AgentException("INDEX_CONFLICT","Index generation changed; retry");
            if(store.replace(scope,current,current.state(status,List.of(),null))){var work=active.get(generation);if(work!=null)work.token.cancel(StopReason.USER_CANCELLED);return;}
            current=store.get(scope,current.videoId());
        }
        throw new AgentException("INDEX_CONFLICT","Concurrent index update; retry");
    }
    public synchronized VideoIndex start(AccessScope scope,long video){
        allowed(scope,video);var existing=get(scope,video);
        if(existing!=null && (existing.active() || existing.status().equals("READY")))return existing;
        if(active.values().stream().filter(w->w.scope.tenantId().equals(scope.tenantId()) && w.scope.userId().equals(scope.userId())).count()>=governance.perUserActive())
            throw new AgentException("CAPACITY_EXCEEDED","At most two video indexes per user can run concurrently");
        var source=port().source(video,scope,control());
        if(source.videoId()!=video || source.durationMs()<=0 || source.durationMs()>governance.maxDurationMillis() || source.sourceVersion()==null || source.sourceVersion().isBlank() || source.sourceVersion().length()>128)
            throw new AgentException("INVALID_EVIDENCE","Invalid source metadata");
        var now=Instant.now();var id=UUID.randomUUID();var job=new VideoIndex(id,video,source.sourceVersion(),source.durationMs(),"QUEUED",0,(int)((source.durationMs()+governance.segmentMillis()-1)/governance.segmentMillis()),List.of(),null,now,now.plusSeconds(governance.indexTimeoutSeconds()));
        var admitted=store.start(scope,job);if(!admitted.jobId().equals(id))return admitted;
        var token=new CancellationToken();var calls=new AtomicInteger();
        var control=new CallControl(job.deadline(),token,new ModelBudget(governance.indexMaxTokens(),governance.tokenReservation()),()->{if(calls.incrementAndGet()>governance.indexMaxCalls())throw new AgentException("MODEL_CALL_BUDGET_EXCEEDED","Index call budget exhausted");});
        var work=new Work(scope,video,token,control);active.put(id,work);
        try{workers.execute(()->process(id,work));}catch(RejectedExecutionException full){active.remove(id);store.replace(scope,job,job.state("FAILED",List.of(),"QUEUE_FULL"));}
        return store.get(scope,video);
    }
    private void process(UUID id,Work work){
        try{
            update(id,work,c->c.state("RUNNING",c.chunks(),null));var current=owned(id,work);
            for(int index=0;;index++){
                work.control.checkpoint();current=owned(id,work);
                if(!Instant.now().isBefore(current.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Index deadline exceeded");
                if(index>=current.total())break;
                long start=index*(long)governance.segmentMillis(),end=Math.min(current.durationMs(),start+governance.segmentMillis());
                VideoContent.Facts facts=null;
                for(int attempt=0;attempt<governance.maxAttempts();attempt++){
                    try{facts=analysis.analyze(work.video,start,end,"提取本段内容供后续检索",work.scope,work.control);break;}
                    catch(AgentException failure){if(attempt==governance.maxAttempts()-1 || !Set.of("MODEL_UNAVAILABLE","VIDEO_CONTENT_UNAVAILABLE").contains(failure.code()))throw failure;work.control.checkpoint();}
                }
                if(!current.sourceVersion().equals(facts.sourceVersion()))throw new AgentException("INDEX_SOURCE_CHANGED","Source changed while indexing");
                var chunk=new VideoIndex.Chunk(index,start,end,facts.summary(),facts.transcript(),facts.coverage(),facts.sampledAtMs());
                update(id,work,c->{var chunks=new ArrayList<>(c.chunks());chunks.add(chunk);return c.state("RUNNING",chunks,null);});
            }
            current=owned(id,work);var latest=port().source(work.video,work.scope,work.control);
            if(!latest.sourceVersion().equals(current.sourceVersion()) || latest.durationMs()!=current.durationMs())throw new AgentException("INDEX_SOURCE_CHANGED","Source changed while indexing");
            update(id,work,c->c.state("READY",c.chunks(),null));
        }catch(Exception error){
            var current=store.get(work.scope,work.video);
            if(current!=null && current.jobId().equals(id) && current.active()){
                try{update(id,work,c->c.state("FAILED",List.of(),error instanceof AgentException e?e.code():"INDEX_FAILED"));}
                catch(AgentException conflict){ /* Cancellation or a newer generation has already won. */ }
            }
        }finally{active.remove(id);}
    }
    private void update(UUID id,Work work,java.util.function.UnaryOperator<VideoIndex> change){for(int i=0;i<governance.casAttempts();i++){var old=owned(id,work);if(store.replace(work.scope,old,change.apply(old)))return;}throw new AgentException("INDEX_CONFLICT","Concurrent index update");}
    private VideoIndex owned(UUID id,Work work){var current=store.get(work.scope,work.video);if(current==null || !current.jobId().equals(id) || !current.active())throw new AgentException("CANCELLED","Index stopped");return current;}
    public VideoIndex cancel(AccessScope scope,long video){
        allowed(scope,video);var current=store.get(scope,video);if(current==null)return null;
        invalidate(scope,current,"CANCELLED");return store.get(scope,video);
    }
    @org.springframework.scheduling.annotation.Scheduled(scheduler="maintenanceScheduler",fixedDelayString="${agent.video.runtime.heartbeat-millis:10000}")
    public void heartbeat(){
        active.forEach((id,work)->{try{var current=owned(id,work);if(!Instant.now().isBefore(current.deadline()))work.token.cancel(StopReason.DEADLINE_EXCEEDED);else store.replace(work.scope,current,current.state(current.status(),current.chunks(),null));}catch(Exception lost){work.token.cancel(StopReason.USER_CANCELLED);}});
    }
    @PreDestroy public void close(){active.forEach((id,work)->{work.token.cancel(StopReason.SHUTDOWN);try{var current=store.get(work.scope,work.video);if(current!=null && current.jobId().equals(id))invalidate(work.scope,current,"CANCELLED");}catch(Exception ignored){ /* The stale lease remains retryable after restart. */ }});workers.shutdownNow();}
}
