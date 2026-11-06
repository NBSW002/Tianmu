package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.AgentException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryConsolidationTest {
    final ObjectMapper json=new ObjectMapper();
    final List<MemoryConsolidation.Source> sources=List.of(
        new MemoryConsolidation.Source("old","r1","s","USER",1,1,"hash1","讲解数据库时，我喜欢先给例子。"),
        new MemoryConsolidation.Source("assistant","r1","s","ASSISTANT",2,1,"hash2","你可能喜欢短回答。"),
        new MemoryConsolidation.Source("new","r2","s","USER",3,2,"hash3","以后讲这个也这样吧。"));
    JsonNode proposal()throws Exception{return json.readTree("""
        {"complete":true,"overflow":false,"processed_message_ids":["new"],"candidates":[
        {"kind":"PREFERENCE","attribute":"explanation_style","qualifier":"","value":"examples_first","text":"讲解数据库时先举例。","topic":"数据库",
        "support":[{"message_id":"old","quote":"讲解数据库时，我喜欢先给例子。"},{"message_id":"new","quote":"以后讲这个也这样吧。"}]}]}
        """);}
    @Test void reanchoredFactsCountAsActualWritesInTrace(){
        var stats=MemoryConsolidation.decisionStats(3,4,Map.of("ADD",1,"REANCHOR_AUTO",1,"SUPERSEDE_AUTO",1));
        assertEquals(3,stats.get("memory.selected_count"));assertEquals(2,stats.get("memory.added_count"));assertEquals(1,stats.get("memory.updated_count"));
        assertEquals(stats,com.tianmu.agent.harness.trace.TraceAttributes.sanitize(stats));
    }
    @Test void naturalPronounFactKeepsBothRequiredUserSources()throws Exception{
        var result=MemoryConsolidation.parse(proposal(),sources,"new");assertEquals(2,result.candidates().get(0).support().size());
        assertEquals("数据库",result.candidates().get(0).topic());
    }
    @Test void assistantInventedOrMissingCurrentSourcesAreRejected()throws Exception{
        var root=proposal();((com.fasterxml.jackson.databind.node.ObjectNode)root.path("candidates").get(0).path("support").get(0)).put("message_id","assistant").put("quote","你可能喜欢短回答。");
        assertThrows(AgentException.class,()->MemoryConsolidation.parse(root,sources,"new"));
        var oldOnly=proposal();((com.fasterxml.jackson.databind.node.ArrayNode)oldOnly.path("candidates").get(0).path("support")).remove(1);
        assertThrows(AgentException.class,()->MemoryConsolidation.parse(oldOnly,sources,"new"));
    }
    @Test void incompleteOverflowAndConflictingSameFactDoNotAdvancePublication()throws Exception{
        var root=proposal();((com.fasterxml.jackson.databind.node.ObjectNode)root).put("overflow",true);
        assertEquals("CANDIDATE_CAPACITY",assertThrows(AgentException.class,()->MemoryConsolidation.parse(root,sources,"new")).code());
        var conflicting=proposal();var items=(com.fasterxml.jackson.databind.node.ArrayNode)conflicting.path("candidates");items.add(items.get(0).deepCopy());
        assertThrows(AgentException.class,()->MemoryConsolidation.parse(conflicting,sources,"new"));
    }
    @Test void inventedTopicAndQuoteAreRejectedWhileEmptyCompleteWindowIsValid()throws Exception{
        var root=proposal();((com.fasterxml.jackson.databind.node.ObjectNode)root.path("candidates").get(0)).put("topic","编程语言");
        assertThrows(AgentException.class,()->MemoryConsolidation.parse(root,sources,"new"));
        var empty=proposal();((com.fasterxml.jackson.databind.node.ArrayNode)empty.path("candidates")).removeAll();
        assertTrue(MemoryConsolidation.parse(empty,sources,"new").candidates().isEmpty());
    }
    @Test void topicCannotBorrowAnUnreferencedOldUserMessage()throws Exception{
        var root=proposal();((com.fasterxml.jackson.databind.node.ArrayNode)root.path("candidates").get(0).path("support")).remove(0);
        assertThrows(AgentException.class,()->MemoryConsolidation.parse(root,sources,"new"));
    }
}
