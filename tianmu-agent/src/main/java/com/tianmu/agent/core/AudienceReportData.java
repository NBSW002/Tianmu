package com.tianmu.agent.core;

import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import java.time.*;
import java.util.*;

/** Historical combined-evidence counts retained for reading persisted evidence. */
public final class AudienceReportData {
    private AudienceReportData() {}
    public record Sources(long danmaku,long danmakuAuthors,long danmakuViewers,long comments,long commentAuthors,
                          long commentViewers,long contextComments,long uniqueViewers,Instant snapshotAt,Set<AnalysisContract.Source> selectedSources) {
        public Sources(long d,long da,long dv,long c,long ca,long cv,long context,long unique,Instant at) {
            this(d,da,dv,c,ca,cv,context,unique,at,Set.of(AnalysisContract.Source.DANMAKU,AnalysisContract.Source.COMMENT));
        }
        public Sources {
            selectedSources = selectedSources == null ? Set.of(AnalysisContract.Source.DANMAKU,AnalysisContract.Source.COMMENT) : Set.copyOf(selectedSources);
            if(selectedSources.isEmpty() || selectedSources.contains(AnalysisContract.Source.VIDEO)
                    || !selectedSources.contains(AnalysisContract.Source.DANMAKU) && danmaku!=0
                    || !selectedSources.contains(AnalysisContract.Source.COMMENT) && (comments!=0 || contextComments!=0))
                throw new IllegalArgumentException("Unselected feedback source has records");
            if(danmaku<0 || danmakuAuthors<0 || danmakuAuthors>danmaku || danmakuViewers<0 || danmakuViewers>danmaku-danmakuAuthors
                    || comments<0 || commentAuthors<0 || commentAuthors>comments || commentViewers<0 || commentViewers>comments-commentAuthors
                    || contextComments<0 || uniqueViewers<Math.max(danmakuViewers,commentViewers) || uniqueViewers>Math.addExact(danmakuViewers,commentViewers)
                    || snapshotAt==null)throw new IllegalArgumentException("Invalid source counts");
        }
    }
    static void validate(Sources s,Overview data,long total) {
        if(data==null || s.danmaku()+s.comments()!=total)throw new IllegalArgumentException("Source coverage mismatch");
        long d=0,c=0,da=0,ca=0;
        for(TextGroup g:data.corpus())if(g.source().equals("COMMENT")){c=Math.addExact(c,g.count());if(g.author())ca=Math.addExact(ca,g.count());}
        else{d=Math.addExact(d,g.count());if(g.author())da=Math.addExact(da,g.count());}
        if(d!=s.danmaku() || c!=s.comments() || da!=s.danmakuAuthors() || ca!=s.commentAuthors())throw new IllegalArgumentException("Source records do not match exact totals");
    }
}
