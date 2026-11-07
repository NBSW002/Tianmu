package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.Group;
import java.util.*;

/** Playback-position counts, never send-date activity, viewers, or a sampled subset of quotes. */
public record FeedbackPlaybackTrend(long startMs,long endMs,long bucketMs,List<Long> counts,long omitted,boolean knownRange) {
    public FeedbackPlaybackTrend { counts=List.copyOf(counts); }
    public long plotted(){return counts.stream().mapToLong(Long::longValue).sum();}
    public long binStart(int index){return startMs+index*bucketMs;}
    public long binEnd(int index){long start=binStart(index);return start+Math.min(bucketMs,endMs-start);}
    public static FeedbackPlaybackTrend build(Asset asset,List<Group> groups){
        long start=0,end=0;boolean known=false;
        if(asset.video()!=null){start=asset.video().startMs();end=asset.video().endMs();known=true;}
        else if(asset.request().videoStartMs()!=null){start=asset.request().videoStartMs();end=asset.request().videoEndMs();known=true;}
        if(!known){long last=groups.stream().map(Group::playbackMs).filter(Objects::nonNull).filter(t->t>=0 && t<Long.MAX_VALUE).mapToLong(Long::longValue).max().orElse(-1);end=last+1;}
        if(end<=start)return new FeedbackPlaybackTrend(0,1,1000,List.of(),asset.snapshot().counts().danmaku(),known);
        long range=end-start,target=(range-1)/24+1,step=target;
        for(long candidate:new long[]{1000,2000,5000,10000,15000,30000,60000,120000,300000,600000,1800000,3600000})if(candidate>=target){step=candidate;break;}
        var bins=new ArrayList<Long>(Collections.nCopies((int)((range-1)/step+1),0L));long omitted=0;
        for(var group:groups){
            Long at=group.playbackMs();
            if(at==null || at<start || at>=end){omitted=Math.addExact(omitted,group.messages());continue;}
            int index=(int)((at-start)/step);bins.set(index,Math.addExact(bins.get(index),group.messages()));
        }
        var result=new FeedbackPlaybackTrend(start,end,step,bins,omitted,known);
        if(Math.addExact(result.plotted(),omitted)!=asset.snapshot().counts().danmaku())throw new com.tianmu.agent.core.AgentException("STORAGE_CORRUPT","Playback counts differ from snapshot");
        return result;
    }
}
