package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackVectorWorkControllerTest {
    @Test void serviceTokenIsRequiredBeforeClaim(@TempDir Path dir)throws Exception {
        var token=dir.resolve("token");Files.writeString(token,"x".repeat(40));
        var work=mock(FeedbackVectorWork.class);var controller=new FeedbackVectorWorkController(work,mock(BusinessFeedbackSnapshot.class),token.toString());
        assertEquals(401,assertThrows(BridgeException.class,()->controller.claim(null)).status());
        assertEquals(401,assertThrows(BridgeException.class,()->controller.claim("wrong")).status());verifyNoInteractions(work);
        assertEquals(204,controller.claim("x".repeat(40)).getStatusCode().value());verify(work).claim();
    }
}
