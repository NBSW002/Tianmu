package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.memory.PersonalMemory.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryLearningPolicyTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void singleWordSaveKeepsTheOriginalUserFactAsRequiredEvidence()throws Exception{
        var sources=List.of(new MemoryConsolidation.Source("fact","r1","s","USER",1,1,"h","我是天津大学的"),new MemoryConsolidation.Source("save","r2","s","USER",3,2,"h2","存"));
        var root=json.readTree("""
            {"complete":true,"overflow":false,"processed_message_ids":["save"],"candidates":[{"kind":"PROFILE","attribute":"school","qualifier":"","value":"天津大学","text":"用户表示自己在天津大学。","topic":"","support":[{"message_id":"fact","quote":"我是天津大学的"},{"message_id":"save","quote":"存"}]}]}
            """);
        assertEquals(2,MemoryConsolidation.parse(root,sources,"save").candidates().get(0).support().size());
        ((com.fasterxml.jackson.databind.node.ArrayNode)root.path("candidates").get(0).path("support")).remove(0);
        assertThrows(AgentException.class,()->MemoryConsolidation.parse(root,sources,"save"));
    }
    @Test void batchCannotPretendOnlyTheNewestMessageWasProcessed()throws Exception{
        var root=json.readTree("{\"complete\":true,\"overflow\":false,\"processed_message_ids\":[\"b\"],\"candidates\":[]}");
        assertThrows(AgentException.class,()->MemoryConsolidation.parse(root,List.of(),List.of("a","b")));
    }
    @Test void confirmationRequiresMemoryContextAndCompositeRequestsContinue(){
        assertFalse(MemoryIntent.save("是的",List.of()));
        var recent=List.of(new MemoryTypes.Message(UUID.randomUUID(),UUID.randomUUID(),1,"user","存",Instant.now()));
        assertTrue(MemoryIntent.save("是的",recent));assertFalse(MemoryIntent.save("不要记住",recent));
        assertTrue(MemoryIntent.save("请记住我在天津大学，顺便分析这个视频",List.of()));
        assertFalse(MemoryIntent.exclusive("请记住我在天津大学，顺便分析这个视频"));
    }
    Entry entry(String text,Kind kind){return new Entry(UUID.randomUUID(),1,1,new Content(Scope.user(),kind,text,null),Instant.now());}
    @Test void unrelatedProfilesAreNotInjectedAndSchoolReferencesCanFindUniversity(){
        var school=entry("用户在天津大学就读",Kind.PROFILE);var strawberry=entry("用户喜欢草莓",Kind.PREFERENCE);
        assertTrue(MemorySelection.rank(List.of(school,strawberry),"Trace为什么串行",null).isEmpty());
        assertEquals(List.of(school),MemorySelection.rank(List.of(school,strawberry),MemorySelection.expand("我学校附近有什么"),null));
    }
    @Test void coreOnlyAcceptsGlobalResponsePreferences(){
        assertTrue(MemorySelection.core("PREFERENCE","response_length","",""));
        assertFalse(MemorySelection.core("PROFILE","school","",""));
        assertFalse(MemorySelection.core("PREFERENCE","video_genre","",""));
        assertFalse(MemorySelection.core("PREFERENCE","response_length","数据库",""));
    }
    @Test void dreamRejectsInventedTargetsAndMergeCycles()throws Exception{
        var a=entry("用户喜欢简短回答",Kind.PREFERENCE);var b=entry("用户偏好简短回答",Kind.PREFERENCE);
        var root=json.createObjectNode().put("complete",true);var list=root.putArray("changes");
        list.addObject().put("id",a.id().toString()).put("version",1).put("action","MERGE").put("target",b.id().toString()).put("reason","同义表达");
        assertEquals(1,MemoryDream.parse(root,List.of(a,b)).size());
        list.addObject().put("id",b.id().toString()).put("version",1).put("action","MERGE").put("target",a.id().toString()).put("reason","同义表达");
        assertThrows(AgentException.class,()->MemoryDream.parse(root,List.of(a,b)));
    }
}
