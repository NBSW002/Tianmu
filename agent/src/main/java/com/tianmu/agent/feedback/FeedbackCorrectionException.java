package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;

/** A rejected assignment is correction input, never an accepted partial result. */
final class FeedbackCorrectionException extends AgentException {
    private final FeedbackModels.Correction correction;
    FeedbackCorrectionException(FeedbackModels.Correction correction){
        super("INVALID_ANALYSIS","Correct "+correction.issue()+" and resubmit the complete bounded result");
        this.correction=correction;
    }
    FeedbackModels.Correction correction(){return correction;}
}
