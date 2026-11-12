package com.tianmu.agent.model;

import java.util.List;

/** Provider-reported discovery metadata, separate from request usage and runtime budgets. */
public interface ModelMetadataPort {
    record Metadata(String provider, String model, Long contextWindow, Long maxOutputTokens, long fetchedAtEpochMs) {}
    List<Metadata> list();
}
