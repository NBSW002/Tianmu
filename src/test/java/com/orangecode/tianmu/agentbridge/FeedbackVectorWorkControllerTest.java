package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackVectorWorkControllerTest {
    @Test void internalLeasePreservesNumericLongsDespiteGlobalStringSerialization()throws Exception {
        var module=new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(Long.class,com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
        module.addSerializer(Long.TYPE,com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(module);
        var node=mapper.valueToTree(new FeedbackVectorWork.Lease(7520745728063299597L,9,"t","tianmu","1"));
        assertTrue(node.get("videoId").isIntegralNumber());assertEquals(7520745728063299597L,node.get("videoId").longValue());assertTrue(node.get("generation").isIntegralNumber());
    }
    @Test void serviceTokenIsRequiredBeforeClaim(@TempDir Path dir)throws Exception {
        var token=dir.resolve("token");Files.writeString(token,"x".repeat(40));
        var work=mock(FeedbackVectorWork.class);var controller=new FeedbackVectorWorkController(work,mock(BusinessFeedbackSnapshot.class),token.toString());
        assertEquals(401,assertThrows(BridgeException.class,()->controller.claim(null)).status());
        assertEquals(401,assertThrows(BridgeException.class,()->controller.claim("wrong")).status());verifyNoInteractions(work);
        assertEquals(204,controller.claim("x".repeat(40)).getStatusCode().value());verify(work).claim();
    }
}
