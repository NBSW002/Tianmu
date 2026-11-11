package com.tianmu.agent.query;

import com.tianmu.agent.core.AgentTypes.AccessScope;

/** Small, authenticated page metadata. Does not imply that video content has been analyzed. */
public interface VideoPagePort {
    record Page(String videoId, String title, long durationMs, String coverDataUrl) {
        public Page(String videoId,String title,long durationMs){this(videoId,title,durationMs,"");}
    }
    Page page(long videoId, AccessScope scope);
    default Page reportPage(long videoId,AccessScope scope){return page(videoId,scope);}
}
