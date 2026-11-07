package com.tianmu.agent.runtime.spi;

import com.tianmu.agent.core.AgentTypes.Answer;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.TurnControl;

/** Transactional response boundary. Evaluators never own reservations or commit terminal state. */
public interface ResponseDelivery {
    TurnControl.Reservation reserve(RunState run);
    void clarification(RunState run,String question,TurnControl.Reservation reservation);
    void answer(RunState run,Answer answer,TurnControl.Reservation reservation,StopReason reason);
}
