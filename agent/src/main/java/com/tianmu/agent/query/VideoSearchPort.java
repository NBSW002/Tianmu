package com.tianmu.agent.query;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.*;
import java.util.*;

/** Author-owned metadata discovery. A returned candidate is not authorization to analyze it. */
public interface VideoSearchPort {
    record Search(String query,LocalDate startDate,LocalDate endDate,int offset,int limit){
        public Search {
            if(query==null || query.length()>120 || offset<0 || offset>1000 || limit<1 || limit>20
                    || (startDate==null)!=(endDate==null) || startDate!=null && !startDate.isBefore(endDate))throw new IllegalArgumentException("Invalid video search");
            query=query.strip();
        }
    }
    record Candidate(String videoId,String title,long durationMs,Instant publishedAt){
        public Candidate{if(videoId==null || !videoId.matches("[1-9][0-9]{0,18}") || title==null || title.length()>300 || durationMs<0 || publishedAt==null)throw new IllegalArgumentException("Invalid candidate");Long.parseLong(videoId);}
    }
    record Page(List<Candidate> items,int nextOffset,boolean hasMore){public Page{items=List.copyOf(items);if(items.size()>20 || nextOffset<0)throw new IllegalArgumentException("Invalid search page");}}
    Page search(Search input,AccessScope owner,CallControl control);
}
