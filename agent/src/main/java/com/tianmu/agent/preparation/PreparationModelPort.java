package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.CallControl;

/** Independent intent and clarification/condition candidates, plus optional emotion. */
public interface PreparationModelPort {
    default String revision(){return "preparation-v1";}
    Intent intent(Input input, CallControl control);
    default IntentRecognition recognizeIntent(Input input, CallControl control) {
        return new IntentRecognition(intent(input,control),null,java.util.List.of(),input.intentCandidates());
    }
    Understanding understand(Input input, CallControl control);
    /** Independent clarification recognition; older adapters retain their structured condition contract. */
    default Understanding clarification(Input input, CallControl control) { return understand(input, control); }
    Emotion emotion(Input input, CallControl control);
}
