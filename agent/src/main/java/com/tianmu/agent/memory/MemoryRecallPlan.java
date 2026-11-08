package com.tianmu.agent.memory;

import java.util.*;

/** Pure host classification. A page's video is never an implicit personal-memory scope. */
public record MemoryRecallPlan(Mode mode,String kind,boolean continuation) {
    public enum Mode { RELATED, OVERVIEW }
    public static MemoryRecallPlan of(String message){
        String q=Objects.toString(message,"").strip();
        boolean continuation=q.matches("(?:请)?(?:继续|继续查看|继续看|下一页|还有呢|还有吗)[。？！!?]*");
        boolean overview=q.matches("(?:请)?(?:你(?:都|还)?(?:了解|知道|认识|记得)我(?:吗|多少|什么|哪些信息)?|你对我(?:了解多少|有多少了解)|关于我你(?:知道|了解|记得)(?:哪些|多少|什么)|你(?:记得|记住|记录|保存)了?(?:我|我的)(?:什么|哪些|多少|信息|资料|偏好|目标|事情|记忆)*|我的(?:信息|资料|记忆|偏好|目标)(?:你知道多少|有哪些|是什么)?|(?:查看|看看|列出|说说)(?:你记住的|我的)(?:信息|资料|记忆|偏好|目标)|我是谁)[。？！!?]*")
                && !q.matches("(?s).*(怎么|如何|为什么|功能|原理|不要|别|不用|[“”\"「」]).*")
                ;
        String kind=q.contains("偏好")?"PREFERENCE":q.contains("目标")?"GOAL":"";
        return new MemoryRecallPlan(overview?Mode.OVERVIEW:Mode.RELATED,kind,continuation);
    }
}
