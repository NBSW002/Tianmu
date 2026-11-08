package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.feedback.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.harness.RuntimeHarness;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackRunningSubmissionTest {
    @Test void runningBeforeTaskAssemblyDoesNotFailOrStartWork(){
        var preparation=mock(FeedbackPreparationService.class);var analysis=mock(FeedbackAnalysisService.class);var runs=mock(RunService.class);var assembler=mock(TaskAssembler.class);
        var run=new RunState(new AccessScope("t","u",Set.of(1001L)),"普通请求");run.start();assertNull(run.task());when(runs.running()).thenReturn(List.of(run));
        var coordinator=new FeedbackPreparationCoordinator(preparation,analysis,runs,assembler,mock(RuntimeHarness.class));
        try{coordinator.advanceSubmissions();assertEquals(Status.RUNNING,run.snapshot().status());verifyNoInteractions(preparation,analysis,assembler);}
        finally{run.cancel();coordinator.close();}
    }
}
