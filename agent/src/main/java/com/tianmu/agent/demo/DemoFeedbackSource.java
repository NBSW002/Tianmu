package com.tianmu.agent.demo;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackSnapshotPort;
import com.tianmu.agent.runtime.control.CallControl;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Offline data goes through the same snapshot and analysis workflow as production. */
@Component @Profile("demo & !business")
public final class DemoFeedbackSource implements FeedbackSnapshotPort {
    public void authorize(AccessScope owner,long videoId){
        if(!owner.videoIds().contains(videoId))throw new AgentException("FORBIDDEN","Video outside demo scope");
    }
    public Receipt export(Request request,AccessScope owner,CallControl control,Consumer<List<Unit>> consumer){
        authorize(owner,request.query().videoId());control.checkpoint();var rows=new ArrayList<Unit>();
        for(Source source:request.sources())for(int i=1;i<=120;i++)rows.add(new Unit((source==Source.COMMENT?"C:":"D:")+i,source,Integer.toString(i),
                "【模拟数据】观众希望补充发货、优惠与商品说明 "+(i%8),"",false,source==Source.DANMAKU?1000L:null,"demo-viewer-"+(i%32),
                request.query().end().minusSeconds(1).atZone(DanmakuTypes.ZONE).toInstant()));
        consumer.accept(List.copyOf(rows));
        return new Receipt(Instant.now(),new Counts(request.sources().contains(Source.COMMENT)?120:0,request.sources().contains(Source.DANMAKU)?120:0,0,0,32));
    }
}
