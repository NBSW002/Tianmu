package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.feedback.FeedbackCompressionProperties;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.tool.ToolCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompressionProtocolPinningTest {
    @Test void embeddingIdentityChangesRejectAnAlreadyFrozenRun(){
        var assembler=new TaskAssembler(new SkillCatalog(),new PromptCatalog(),new ToolCatalog());assembler.compressionPolicy(enabled());
        var embedding=org.mockito.Mockito.mock(com.tianmu.agent.feedback.FeedbackEmbedding.class);
        var vectors=org.mockito.Mockito.mock(com.tianmu.agent.feedback.FeedbackVectorIndex.class);
        org.mockito.Mockito.when(embedding.version()).thenReturn("embed-v1");org.mockito.Mockito.when(embedding.dimension()).thenReturn(3);org.mockito.Mockito.when(vectors.dimension()).thenReturn(3);
        var compression=new com.tianmu.agent.feedback.FeedbackCompression(embedding,vectors,enabled(),new com.fasterxml.jackson.databind.ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(assembler,"compressionRuntime",compression);
        var current=run();var task=assembler.assemble(current);assertDoesNotThrow(()->assembler.verify(task));
        org.mockito.Mockito.when(embedding.version()).thenReturn("embed-v2");
        assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->assembler.verify(task)).code());current.cancel();
    }
    RunState run(){var r=new RunState(new AccessScope("demo","alice",Set.of(1001L)),"只分析视频1001最近30天评论和弹幕，生成报告，不读取视频内容");r.start();r.prepared(new Result(Route.HARNESS,new PreparedRequest(r.message,r.query(),Emotion.NEUTRAL,Set.of()),null,null));return r;}
    FeedbackCompressionProperties enabled(){return new FeedbackCompressionProperties(true,3,15000,.90,32,.75,2,3,60,.96,300,600,32768,67108864,512);}
    @Test void featureToggleDoesNotUpgradeAlreadyAssembledLegacyTask(){
        var assembler=new TaskAssembler(new SkillCatalog(),new PromptCatalog(),new ToolCatalog());
        var old=run();var oldTask=assembler.assemble(old);assertFalse(oldTask.resourceVersions().containsKey(TaskAssembler.COMPRESSION_RESOURCE));
        assembler.compressionPolicy(enabled());assertDoesNotThrow(()->assembler.verify(oldTask));assertTrue(assembler.resourceVersions().containsKey(TaskAssembler.COMPRESSION_RESOURCE));
        var current=run();var currentTask=assembler.assemble(current);assertTrue(currentTask.resourceVersions().containsKey(TaskAssembler.COMPRESSION_RESOURCE));assertDoesNotThrow(()->assembler.verify(currentTask));
        assembler.compressionPolicy(FeedbackCompressionProperties.defaults());assertDoesNotThrow(()->assembler.verify(oldTask));
        assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->assembler.verify(currentTask)).code());old.cancel();current.cancel();
    }
}
