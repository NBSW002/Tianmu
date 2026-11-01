package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeToolHistoryTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    ModelContext context(List<ToolResult> observations, List<ToolExchange> exchanges, MemoryTypes.View memory) {
        return new ModelContext("当前任务",LocalDate.now(),new AccessScope("t","u",Set.of(1001L)),Set.of(),observations,List.of(),6,
                false,"",null,null,memory,exchanges);
    }
    List<Map<String,Object>> build(List<ToolResult> observations,List<ToolExchange> exchanges) {
        return new HarnessContextBuilder(json,ContextPolicy.defaults()).build("rules",context(observations,exchanges,MemoryTypes.View.empty()));
    }
    List<String> replyIds(List<Map<String,Object>> messages) {
        return messages.stream().filter(m->"tool".equals(m.get("role"))).map(m->m.get("tool_call_id").toString()).toList();
    }
    List<JsonNode> calls(List<Map<String,Object>> messages) {
        List<JsonNode> calls=new ArrayList<>();
        for(var message:messages)for(var call:json.valueToTree(message).path("tool_calls"))calls.add(call);
        return calls;
    }
    void paired(List<Map<String,Object>> messages) {
        var expected=new ArrayList<String>();
        for(var call:calls(messages))expected.add(call.path("id").asText());
        assertEquals(expected,replyIds(messages));assertEquals(expected.size(),new HashSet<>(expected).size());
    }
    @Test void equalAndEvenSharedResultInstancesKeepEachDistinctCallAndItsActualArguments() throws Exception {
        var result=new ToolResult("read_history",Map.of("messages",List.of(),"more_available",false),null);
        var first=new ToolCall("read_history",Map.of("limit",3),"call_a");
        var second=new ToolCall("read_history",Map.of("limit",4),"call_b");
        var messages=build(List.of(result,result),List.of(new ToolExchange(first,result),new ToolExchange(second,result)));
        assertEquals(List.of("call_a","call_b"),replyIds(messages));paired(messages);
        assertEquals(3,json.readTree(calls(messages).get(0).path("function").path("arguments").asText()).path("limit").asInt());
        assertEquals(4,json.readTree(calls(messages).get(1).path("function").path("arguments").asText()).path("limit").asInt());
    }
    @Test void equalResultsDoNotMergeSeparateNativeReadGroups() {
        var result=new ToolResult("query_audience",Map.of("count",0),null);
        var observations=new ArrayList<ToolResult>();var exchanges=new ArrayList<ToolExchange>();
        for(int i=1;i<=4;i++) {
            var call=new ToolCall("query_audience",Map.of("video_id",i),"p"+i,i<=2?"g1":"g2");
            observations.add(result);exchanges.add(new ToolExchange(call,result));
        }
        var messages=build(observations,exchanges);
        assertEquals(List.of("p1","p2","p3","p4"),replyIds(messages));paired(messages);
        assertEquals(List.of(2,2),messages.stream().filter(m->m.containsKey("tool_calls")).map(m->((List<?>)m.get("tool_calls")).size()).toList());
    }
    @Test void historyTrimmingAndClassifiedBodyReleaseKeepCallsGroupsAndUnreadDataWithoutMutatingRecords() throws Exception {
        var first=new ToolResult("query_audience",Map.of("evidence_id","ev_a","classification",Map.of("offset",0,"groups",List.of(Map.of("text","旧原文".repeat(5000))))),"ev_a");
        var video=new ToolResult("analyze_video_content",Map.of("summary","画面"),"ev_v");
        var last=new ToolResult("create_danmaku_report",Map.of("evidence_id","ev_a","classification",Map.of("offset",40,"total_groups",80,"groups",List.of(Map.of("text","未读原文")))),"ev_a");
        var exchanges=List.of(new ToolExchange(new ToolCall(first.tool(),Map.of(),"q1","batch"),first),
                new ToolExchange(new ToolCall(video.tool(),Map.of("video_id",1001),"v1","batch"),video),
                new ToolExchange(new ToolCall(last.tool(),Map.of("offset",0,"evidence_id","ev_a","topics_json","[]"),"r1"),last));
        var turn=UUID.randomUUID();var memory=new MemoryTypes.View(1,"",List.of(new MemoryTypes.Message(UUID.randomUUID(),turn,1,"user","old question",Instant.now())),false,"");
        var context=context(List.of(first,video,last),exchanges,memory);String original=json.writeValueAsString(context);
        var messages=new HarnessContextBuilder(json,new ContextPolicy(4096,0)).build("rules",context);
        assertEquals(List.of("q1","v1","r1"),replyIds(messages));paired(messages);
        String output=json.writeValueAsString(messages);assertFalse(output.contains("旧原文"));assertFalse(output.contains("old question"));assertTrue(output.contains("未读原文"));
        assertEquals(original,json.writeValueAsString(context));
    }
    @Test void legacyMissingIdsRemainStableAndNeverCollideWithRealProviderIds() {
        var result=new ToolResult("read_history",Map.of(),null);
        var exchanges=List.of(new ToolExchange(new ToolCall(result.tool(),Map.of()),result),
                new ToolExchange(new ToolCall(result.tool(),Map.of(),"call_host_0"),result));
        var messages=build(List.of(result,result),exchanges);paired(messages);
        assertEquals("call_host_0",replyIds(messages).get(1));assertNotEquals("call_host_0",replyIds(messages).get(0));
        assertEquals(messages,build(List.of(result,result),exchanges));
        var legacy=build(List.of(result,result),List.of());paired(legacy);
        assertEquals(List.of("call_host_0","call_host_1"),replyIds(legacy));
    }
    @Test void mismatchedRecordsDuplicateIdsAndBrokenGroupsFailInsteadOfGuessingAnotherCall() {
        var a=new ToolResult("read_history",Map.of("value","a"),null);var b=new ToolResult("read_history",Map.of("value","b"),null);
        var ca=new ToolCall(a.tool(),Map.of(),"a");var cb=new ToolCall(b.tool(),Map.of(),"b");
        var invalid=new ArrayList<List<ToolExchange>>();
        invalid.add(List.of(new ToolExchange(ca,a)));
        invalid.add(List.of(new ToolExchange(ca,b),new ToolExchange(cb,a)));
        invalid.add(List.of(new ToolExchange(new ToolCall("other",Map.of(),"a"),a),new ToolExchange(cb,b)));
        invalid.add(List.of(new ToolExchange(ca,a),new ToolExchange(new ToolCall(b.tool(),Map.of(),"a"),b)));
        invalid.add(List.of(new ToolExchange(null,a),new ToolExchange(cb,b)));
        invalid.add(List.of(new ToolExchange(ca,null),new ToolExchange(cb,b)));
        invalid.add(List.of(new ToolExchange(new ToolCall(a.tool(),Map.of(),""),a),new ToolExchange(cb,b)));
        for(var exchanges:invalid)assertEquals("INVALID_MODEL_CONTEXT",assertThrows(AgentException.class,()->build(List.of(a,b),exchanges)).code());
        var reopened=List.of(new ToolExchange(new ToolCall(a.tool(),Map.of(),"a","g"),a),
                new ToolExchange(cb,b),new ToolExchange(new ToolCall(a.tool(),Map.of(),"c","g"),a));
        assertEquals("INVALID_MODEL_CONTEXT",assertThrows(AgentException.class,()->build(List.of(a,b,a),reopened)).code());
        var tooMany=new ArrayList<ToolExchange>();for(int i=0;i<5;i++)tooMany.add(new ToolExchange(new ToolCall(a.tool(),Map.of(),"p"+i,"g"),a));
        assertEquals("INVALID_MODEL_CONTEXT",assertThrows(AgentException.class,()->build(Collections.nCopies(5,a),tooMany)).code());
    }
}
