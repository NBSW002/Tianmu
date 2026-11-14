package com.tianmu.agent.interaction;

import com.tianmu.agent.core.AnalysisContract;
import com.tianmu.agent.core.AgentTypes.ToolCall;
import com.tianmu.agent.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreatorDraftArgumentsTest {
    private ToolCall call(String tool, Object topic, boolean present) {
        Map<String,Object> item = new LinkedHashMap<>(); item.put("text","一条评论");
        if (tool.equals("prepare_danmaku_draft")) item.put("playback_ms",1000);
        if (present) item.put("feedback_topic_id",topic);
        return new ToolCall(tool,Map.of("video_id",1001,"video_evidence_id","video-evidence","items",List.of(item)),"provider-id");
    }
    @Test void blankOptionalTopicsAreRemovedBeforeSchemaValidationWithoutMutatingOriginal() {
        for (String name : List.of("prepare_comment_draft","prepare_danmaku_draft")) for(Object value : Arrays.asList(null,"", "  ")) {
            ToolCall original=call(name,value,true), normalized=CreatorDraftPreparation.normalize(original);
            assertTrue(ToolArguments.inspect(normalized,new ToolCatalog().all().get(name)).valid());
            assertFalse(((Map<?,?>)((List<?>)normalized.arguments().get("items")).get(0)).containsKey("feedback_topic_id"));
            assertTrue(((Map<?,?>)((List<?>)original.arguments().get("items")).get(0)).containsKey("feedback_topic_id"));
            assertEquals("provider-id",normalized.providerCallId());
        }
    }
    @Test void nonemptyTopicWithoutSelectedFeedbackGetsCorrectableIssue() {
        var context=mock(ToolContext.class);
        var result=CreatorDraftPreparation.preflight(call("prepare_comment_draft","topic-1",true),context);
        assertEquals(ToolArguments.Problem.FEEDBACK_NOT_SELECTED,result.issues().get(0).code());
        assertEquals("items[0].feedback_topic_id",result.issues().get(0).path());
        when(context.analysisContract()).thenReturn(new AnalysisContract(Set.of(AnalysisContract.Source.COMMENT),true));
        assertTrue(CreatorDraftPreparation.preflight(call("prepare_comment_draft","topic-1",true),context).valid());
    }
}
