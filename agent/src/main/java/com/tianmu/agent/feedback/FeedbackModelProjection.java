package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import java.util.*;

/** One projection for both model input and representative payload quotas. */
public final class FeedbackModelProjection {
    private FeedbackModelProjection() {}
    public static Map<String,Object> group(FeedbackModels.Group g,ObjectMapper json) {
        var row=new LinkedHashMap<String,Object>();row.put("id",g.id());row.put("source",g.source());row.put("text",g.text());
        row.put("reply_context",g.replyContext());row.put("author",g.author());row.put("playback_ms",g.playbackMs());row.put("count",g.messages());row.put("candidate_cluster",g.candidateCluster());
        if(g.candidateCluster()!=null && g.id().equals(g.candidateCluster()+"_"+g.source().name())) {
            try{row.remove("text");row.put("representatives",json.readTree(g.text()));row.put("representative_mode",true);}
            catch(Exception invalid){throw new AgentException("STORAGE_CORRUPT","Invalid representative model projection");}
        }
        return row;
    }
}
