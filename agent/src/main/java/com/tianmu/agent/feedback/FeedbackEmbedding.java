package com.tianmu.agent.feedback;

import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;

/** Version covers model revision, dimensions, input framing and normalization. No labels are produced. */
public interface FeedbackEmbedding {
    String version();
    default int dimension(){return 0;}
    List<List<Float>> embed(List<String> inputs,CallControl control);
}
