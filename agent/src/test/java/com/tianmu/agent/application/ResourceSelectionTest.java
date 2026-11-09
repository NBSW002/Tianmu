package com.tianmu.agent.application;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import java.time.*;
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
    @Test void reportLookupCannotTreatOldMessagesOrVideoCandidatesAsItsCurrentReportEvidence(){
        var task=new TaskTypes.Specification("resource.lookup",Map.of("report_reference_required",true),Map.of());
        assertTrue(ResourceSelection.requiresEvidence(task,List.of()));
        assertTrue(ResourceSelection.requiresEvidence(task,List.of(new ToolResult("read_history",Map.of(),null))));
        assertTrue(ResourceSelection.requiresEvidence(task,List.of(new ToolResult("search_my_videos",Map.of(),"ev_candidates"))));
        assertFalse(ResourceSelection.requiresEvidence(task,List.of(new ToolResult("read_report",Map.of(),"ev_report"))));
        assertFalse(ResourceSelection.requiresEvidence(new TaskTypes.Specification("conversation",Map.of(),Map.of()),List.of()));
    }
    ModelContext context(List<String> tools,List<ToolResult> results){
        String text="根据上次报告给三个选题";var scope=new AccessScope("demo","alice",Set.of(1001L));
        var spec=new TaskTypes.Specification("resource.lookup",Map.of("report_reference_required",true),Map.of());
        var prep=new PreparedRequest(text,text,Emotion.UNKNOWN,Set.of());
        var task=new TaskTypes.PreparedTask(UUID.randomUUID(),text,text,LocalDate.now(),"Asia/Shanghai",scope,prep,spec,Map.of(),Set.copyOf(tools),Instant.now().plusSeconds(30),16);
        return new ModelContext(text,LocalDate.now(),scope,Set.of(),results,tools.stream().map(t->new ToolDefinition(t,"",Map.of())).toList(),6,false,"",prep,task);
    }
    @Test void reportDiscoveryProgressesFromActivationToReferenceToReadWithoutHistorySearchLoops(){
        var general=List.of("read_skill","ask_clarification");
        var active=List.of("read_skill","read_history","ask_clarification","read_report");
        assertEquals("read_skill",ResourceSelection.requiredTool(context(general,List.of())));
        assertEquals("read_skill",ResourceSelection.requiredTool(context(List.of("read_skill","ask_clarification","read_report"),List.of())));
        assertEquals("read_history",ResourceSelection.requiredTool(context(active,List.of())));
        var found=new ToolResult("read_history",Map.of("more_available",false,"messages",List.of(Map.of("text","/api/agent/runs/11111111-1111-1111-1111-111111111111/reports/rp_22222222-2222-2222-2222-222222222222"))),null);
        assertEquals("read_report",ResourceSelection.requiredTool(context(active,List.of(found))));
        assertNull(ResourceSelection.requiredTool(context(active,List.of(found,new ToolResult("read_report",Map.of(),"ev_report")))));
        assertEquals("ask_clarification",ResourceSelection.requiredTool(context(active,List.of(new ToolResult("read_history",Map.of("more_available",false,"messages",List.of()),null)))));
    }
}
