package com.tianmu.agent.application;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResourceSelectionTest {
    @Test void zeroMatchesAndPreSelectionAnswersStayClarificationsWhileHistoryAndListsAreUnaffected(){
        var required=new TaskTypes.Specification("resource.lookup",Map.of("selection_required",true),Map.of());
        assertTrue(ResourceSelection.question(required,List.of()).orElseThrow().contains("尚未找到"));
        assertTrue(ResourceSelection.question(new TaskTypes.Specification("resource.lookup",Map.of(),Map.of()),List.of()).isEmpty());
        var first=new ToolResult("search_my_videos",Map.of("items",List.of(Map.of("title","旧搜索","videoId","1002"))),null);
        var last=new ToolResult("search_my_videos",Map.of("items",List.of(Map.of("title","新搜索","videoId","1001"))),null);
        var question=ResourceSelection.question(required,List.of(first,last)).orElseThrow();
        assertTrue(question.contains("新搜索"));assertTrue(question.contains("视频1001"));assertFalse(question.contains("旧搜索"));
    }
}
