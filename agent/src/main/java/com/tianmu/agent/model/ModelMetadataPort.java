package com.tianmu.agent.model;

import java.util.List;

/** Provider-reported discovery metadata, separate from request usage and runtime budgets. */
public interface ModelMetadataPort {
    record Metadata(String provider, String model, Long contextWindow, Long maxOutputTokens, long fetchedAtEpochMs, String name) {
        public Metadata(String provider,String model,Long contextWindow,Long maxOutputTokens,long fetchedAtEpochMs){this(provider,model,contextWindow,maxOutputTokens,fetchedAtEpochMs,null);}
    }
    List<Metadata> list();
}
