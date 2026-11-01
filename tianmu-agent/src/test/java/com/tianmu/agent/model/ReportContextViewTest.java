package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportContextViewTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    Map<String,Object> classification(int offset,int records,String text) {
        return Map.of("offset",offset,"total_groups",120,"processed_groups",offset,"processed_messages",offset,
                "groups",List.of(Map.of("id","G"+(offset+1),"text",text,"original_records",records)),
                "topics",List.of(Map.of("topic_key","T1","observation","累计主题".repeat(120),"messages",offset)));
    }
    ToolResult result(String tool,String evidence,int offset,int records,String text) {
        return new ToolResult(tool,Map.of("evidence_id",evidence,"classification",classification(offset,records,text)),evidence);
    }
    ToolCall report(String id,String evidence,int offset) {
        var topics=new ArrayList<Map<String,Object>>();
        for(int i=0;i<6;i++)topics.add(Map.of("topic_key","T"+(i+1),"title","主题 "+i,"observation","已接受分析".repeat(100),
                "recommendation","继续改进".repeat(100),"sample_ids",List.of("G"+(offset+i+1))));
        try { return new ToolCall("create_danmaku_report",Map.of("evidence_id",evidence,"offset",offset,"video_evidence_id","ev_video","topics_json",json.writeValueAsString(topics)),id); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    ModelContext context(List<ToolExchange> exchanges) {
        return new ModelContext("只分析已确认来源",LocalDate.now(),new AccessScope("t","u",Set.of(1001L)),Set.of(),
                exchanges.stream().map(ToolExchange::result).toList(),List.of(),6,false,"",null,null,MemoryTypes.View.empty(),exchanges);
    }
    List<Map<String,Object>> build(List<ToolExchange> exchanges,int budget) {
        return new HarnessContextBuilder(json,new ContextPolicy(budget,0)).build("rules",context(exchanges));
    }
    JsonNode reply(List<Map<String,Object>> messages,String id) throws Exception {
        return json.readTree(messages.stream().filter(m->id.equals(m.get("tool_call_id"))).findFirst().orElseThrow().get("content").toString());
    }
    JsonNode arguments(List<Map<String,Object>> messages,String id) throws Exception {
        for(var message:messages)for(var call:json.valueToTree(message).path("tool_calls"))
            if(id.equals(call.path("id").asText()))return json.readTree(call.path("function").path("arguments").asText());
        throw new AssertionError("Missing call "+id);
    }
    @Test void acceptedLargeArgumentsAndSupersededTextLeaveTheViewWhileCurrentGroupsAndCanonicalTopicsRemain() throws Exception {
        var query=new ToolExchange(new ToolCall("query_audience",Map.of("video_id",1001),"query"),result("query_audience","ev_a",0,40,"已消费原文".repeat(1800)));
        var accepted=new ToolExchange(report("accepted","ev_a",0),result("create_danmaku_report","ev_a",40,40,"当前未读反馈"));
        var context=context(List.of(query,accepted));String original=json.writeValueAsString(context);
        assertTrue(json.writeValueAsBytes(accepted.call().arguments()).length>12000);
        var messages=new HarnessContextBuilder(json,new ContextPolicy(12000,0)).build("rules",context);
        assertFalse(arguments(messages,"accepted").has("topics_json"));assertEquals("ev_video",arguments(messages,"accepted").path("video_evidence_id").asText());
        var receipt=reply(messages,"accepted").path("data");assertEquals("topics_json",receipt.path("context_projection").path("input_fields_omitted").get(0).asText());
        assertEquals(40,receipt.path("classification").path("offset").asInt());assertTrue(receipt.path("classification").path("topics").toString().contains("累计主题"));
        assertTrue(reply(messages,"query").path("data").path("classification").path("superseded").asBoolean());
        assertTrue(json.writeValueAsString(messages).contains("当前未读反馈"));assertFalse(json.writeValueAsString(messages).contains("已消费原文"));
        assertTrue(json.writeValueAsBytes(messages).length<12000);assertEquals(original,json.writeValueAsString(context));
    }
    @Test void incompleteRejectedOrUnmatchedReceiptsCannotHideTheirSubmittedArguments() throws Exception {
        var call=report("call","ev_a",40);
        var candidates=List.of(new ToolResult(call.name(),Map.of("error_code","INVALID_REPORT","error_message","需要修正"),null),
                result(call.name(),"ev_a",40,40,"未推进"),result(call.name(),"ev_other",80,40,"另一份证据"));
        for(var candidate:candidates) {
            var messages=build(List.of(new ToolExchange(call,candidate)),200000);
            assertEquals(call.arguments().get("topics_json"),arguments(messages,"call").path("topics_json").asText());
            assertFalse(reply(messages,"call").path("data").has("context_projection"));
        }
    }
    @Test void partiallyConsumedCompressedGroupsStayReadableAndAnotherEvidenceCannotSupersedeThem() throws Exception {
        var old=new ToolExchange(new ToolCall("query_audience",Map.of(),"old"),result("query_audience","ev_a",0,80,"尚有未读原文".repeat(100)));
        var accepted=new ToolExchange(report("accepted","ev_a",0),result("create_danmaku_report","ev_a",40,40,"next"));
        var messages=build(List.of(old,accepted),200000);
        assertTrue(reply(messages,"old").path("data").path("classification").has("groups"));
        var other=new ToolExchange(report("other","ev_b",0),result("create_danmaku_report","ev_b",80,40,"other"));
        messages=build(List.of(old,other),200000);assertTrue(reply(messages,"old").path("data").path("classification").has("groups"));
    }
    @Test void genericCommentPagesHistoryAndSkillInstructionsAreNotDeclaredConsumedByReportProgress() throws Exception {
        var accepted=new ToolExchange(report("accepted","ev_a",0),result("create_danmaku_report","ev_a",40,40,"current"));
        for(String name:List.of("query_video_comments","read_history","read_result","read_skill")) {
            var data=new LinkedHashMap<String,Object>(result(name,"ev_a",0,40,"必需原文或流程".repeat(200)).data());
            data.put("page",Map.of("comments",List.of("保留评论")));data.put("skill",Map.of("instructions","保留能力流程"));
            var old=new ToolExchange(new ToolCall(name,Map.of(),"old"),new ToolResult(name,data,"ev_a"));
            assertEquals(json.valueToTree(old.result().data()),reply(build(List.of(old,accepted),200000),"old").path("data"));
        }
    }
    @Test void projectionKeepsNativeGroupAndReplyIdsAndDoesNotInventCompletionOrDiscardOversizedUnreadMaterial() throws Exception {
        var firstCall=report("r1","ev_a",0);firstCall=new ToolCall(firstCall.name(),firstCall.arguments(),"r1","group");
        var first=new ToolExchange(firstCall,result(firstCall.name(),"ev_a",40,40,"next"));
        var second=new ToolExchange(new ToolCall("analyze_video_content",Map.of("video_id",1001),"v1","group"),new ToolResult("analyze_video_content",Map.of("text","画面"),"ev_video"));
        var messages=build(List.of(first,second),12000);
        assertEquals(2,messages.stream().filter(m->m.containsKey("tool_calls")).map(m->((List<?>)m.get("tool_calls")).size()).findFirst().orElseThrow());
        assertEquals(List.of("r1","v1"),messages.stream().filter(m->"tool".equals(m.get("role"))).map(m->m.get("tool_call_id")).toList());
        assertFalse(reply(messages,"r1").path("data").has("status"));assertFalse(reply(messages,"r1").path("data").has("report_document"));
        var huge=new ToolExchange(report("r1","ev_a",0),result("create_danmaku_report","ev_a",40,40,"未读内容".repeat(5000)));
        assertEquals("MODEL_CONTEXT_TOO_LARGE",assertThrows(AgentException.class,()->build(List.of(huge),12000)).code());
    }
    @Test void tinyAcceptedInputsRemainIntactWhenAProjectionReceiptWouldMakeThemLarger() throws Exception {
        var call=new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_a","offset",0,"topics_json","[]"),"r");
        var messages=build(List.of(new ToolExchange(call,result(call.name(),"ev_a",40,40,"next"))),12000);
        assertEquals("[]",arguments(messages,"r").path("topics_json").asText());
        assertFalse(reply(messages,"r").path("data").has("context_projection"));
    }
}
