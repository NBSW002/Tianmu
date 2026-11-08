package com.tianmu.agent.memory;

import com.tianmu.agent.core.*;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.tianmu.agent.tool.ToolCatalog;
import com.tianmu.agent.skill.SkillCatalog;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryHostTest {
    @Test void naturalMemoryDeletionRequestsGoToManagementWithoutTakingBusinessActions(){
        for(var q:List.of("请忘记我的学校","忘掉我的名字","修改我的偏好","清空我的记忆"))assertTrue(MemoryIntent.manageInUi(q),q);
        for(var q:List.of("我忘记了密码","删除我的视频","解释一下忘记我的意思","不要忘记我的学校"))assertFalse(MemoryIntent.manageInUi(q),q);
        for(var q:List.of("你了解我吗","请忘记我的学校","继续查看"))assertFalse(MemoryIntent.automaticCandidate(q),q);
        assertTrue(MemoryIntent.automaticCandidate("我来自天津大学，以后回答请简洁一些。"));
    }
    @Test void totalRecallQuestionsAreNotBusinessOrQuotedInstructions(){
        for(var q:List.of("你了解我吗","我的信息你知道多少","你记得我的什么偏好","查看我的记忆","我是谁"))assertEquals(MemoryRecallPlan.Mode.OVERVIEW,MemoryRecallPlan.of(q).mode(),q);
        for(var q:List.of("你知道我上传的视频有多少评论吗","解释一下‘你了解我吗’这句话","记忆功能是什么","不要查看我的记忆","请记住我的学校","分析这个视频"))assertEquals(MemoryRecallPlan.Mode.RELATED,MemoryRecallPlan.of(q).mode(),q);
    }
    @Test void modelCatalogContainsNoMemoryActionsOrSkill(){
        var tools=new ToolCatalog();for(var n:List.of("search_memory","read_memory","remember_memory","forget_memory"))assertFalse(tools.all().containsKey(n));
        assertFalse(new SkillCatalog().names().contains("personal-memory"));
    }
    Entry entry(String text){return new Entry(UUID.randomUUID(),1,1,new Content(Scope.user(),Kind.PROFILE,text,null),Instant.now());}
    @Test void overviewNeverCombinesIndependentIdentityFactsOrPromisesWrites(){
        String answer=MemoryHostService.render(List.of(entry("用户来自天津大学。"),entry("用户是一名学生。")),false,false,"").text();
        assertTrue(answer.contains("用户来自天津大学。"));assertTrue(answer.contains("用户是一名学生。"));assertFalse(answer.contains("天津大学的学生"));assertFalse(answer.contains("我会记下来"));
    }
    @Test void privateTextCannotBecomeMarkdownLinksOrHtml(){
        String answer=MemoryHostService.render(List.of(entry("[秘密](https://example.com) <script>x</script>")),true,false,"").text();
        assertTrue(answer.contains("\\[秘密\\]"));assertFalse(answer.contains("<script>"));assertTrue(answer.contains("继续查看"));
    }
    @Test void fusionKeepsSemanticOnlyCandidatesAndDeduplicates(){
        var a=entry("喜欢具体例子");var b=entry("喜欢清晰解释");var fused=MemorySelection.fuse(List.of(a),List.of(b,a));
        assertEquals(2,fused.size());assertEquals(a.id(),fused.get(0).id());
    }
    @Test void queryVectorValidationRejectsInvalidProviderResults(){
        assertThrows(AgentException.class,()->MemorySemanticIndex.vector(List.of(Float.NaN,1f)));
        assertThrows(AgentException.class,()->MemorySemanticIndex.vector(List.of(0f,0f)));
        assertEquals(1,MemorySemanticIndex.cosine(MemorySemanticIndex.vector(List.of(1f,1f)),MemorySemanticIndex.vector(List.of(1f,1f))),0.00001);
    }
}
