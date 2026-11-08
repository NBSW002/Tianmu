package com.tianmu.agent.memory;

import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;

/** Adapter-owned model protocol; memory vectors never share the feedback collection. */
public interface MemoryEmbedding {
    String version();
    int dimension();
    List<List<Float>> embed(List<String> texts,CallControl control);
}
