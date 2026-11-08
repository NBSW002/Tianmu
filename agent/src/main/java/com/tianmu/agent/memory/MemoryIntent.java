package com.tianmu.agent.memory;

import com.tianmu.agent.core.MemoryTypes;
import java.util.*;

/** Only schedules a source-grounded background decision; never authorizes model-proposed facts. */
public final class MemoryIntent {
    private MemoryIntent(){}
    public static boolean management(String text){return text!=null && text.matches("(?s).*(记住|记一下|记下|记忆|忘记|忘掉|存一下|存起来).*");}
    public static boolean manageInUi(String text){
        String q=Objects.toString(text,"").strip();
        return q.matches("(?s)^(?:请|帮我|麻烦)*(?:忘记|忘掉)(?:我|学校|姓名|名字|偏好|目标|这条记忆).*")
            || q.matches("(?s)^(?:请|帮我|麻烦)*(?:删除|修改|更正|更新|清空).*(?:记忆|我的信息|我的偏好|我的姓名|我的名字|我的学校|我的目标).*" );
    }
    public static boolean automaticCandidate(String text){
        var plan=MemoryRecallPlan.of(text);
        return !manageInUi(text) && plan.mode()!=MemoryRecallPlan.Mode.OVERVIEW && !plan.continuation();
    }
    public static boolean exclusive(String text){
        if(text.matches("(?s).*(顺便|同时|另外|然后|并且|再帮|也帮|分析|生成报告|搜索|推荐视频|给我|帮我看看|告诉我|解释一下|列一下).*"))return false;
        String rest=text.strip().replaceFirst("^(?:帮我|请|麻烦)?(?:记住|记一下|记下|保存到记忆|存入记忆)[：:，,\\s]*","");
        return !rest.matches("(?s).*[,，;；\\n].*");
    }
    public static boolean save(String text,List<MemoryTypes.Message> recent){
        if(text==null)return false;
        String value=text.strip();
        if(value.matches("(?s).*(不要记|别记|不用记|不必记|不想记|不要保存|别保存|先不存|不存了|忘记|忘掉).*" ) || value.matches("^(怎么|如何|为什么|能不能|能否|要不要).*"))return false;
        if(value.matches("^(?:帮我|请|麻烦)?(?:记住|记一下|记下|保存到记忆|存入记忆)[：:，,\\s]?.+" ) && !value.matches("(?s).*[？?吗].*"))return true;
        if(value.matches("(?s).*(帮我记住|请记住|记住我|记一下|记下来|存入记忆|保存到记忆).*"))return true;
        if(value.matches("^(记住|记住了|存|保存|存一下|存起来|记下)[。！!]*$"))return true;
        if(!value.matches("^(是的|对|好|好的|确认)[。！!]*$"))return false;
        // A confirmation needs an actual memory request in the immediately preceding exchange.
        var tail=recent.stream().skip(Math.max(0,recent.size()-2)).toList();
        return tail.stream().anyMatch(m->"user".equalsIgnoreCase(m.role()) && save(m.text(),List.of()))
            || tail.stream().anyMatch(m->"assistant".equalsIgnoreCase(m.role()) && m.text().matches("(?s).*(存进|保存|记住|记忆).*[？?].*"));
    }
}
