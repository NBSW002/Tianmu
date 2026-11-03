package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentTypes.ToolResult;
import com.tianmu.agent.core.TaskTypes.Specification;
import java.util.*;

/** A discovery turn cannot complete an analysis while its target is still unresolved. */
public final class ResourceSelection {
    private ResourceSelection(){}
    public static boolean requiresEvidence(Specification task,List<ToolResult> observations){
        if(task==null || !"resource.lookup".equals(task.type()))return false;
        boolean report=Boolean.TRUE.equals(task.conditions().get("report_reference_required"));
        return observations.stream().noneMatch(r->r.evidenceId()!=null && (!report || "read_report".equals(r.tool())));
    }
    public static Optional<String> question(Specification task,List<ToolResult> observations){
        if(task==null || !"resource.lookup".equals(task.type()) || !Boolean.TRUE.equals(task.conditions().get("selection_required")))return Optional.empty();
        List<?> candidates=List.of();
        for(var result:observations)if("search_my_videos".equals(result.tool()) && result.data().get("items") instanceof List<?> rows)candidates=rows;
        var question=new StringBuilder(candidates.isEmpty()?"尚未找到可确定的目标视频。":"请确认要分析的视频：");
        for(Object item:candidates.stream().limit(5).toList())if(item instanceof Map<?,?> row){
            String title=Objects.toString(row.get("title"),"");int end=Math.min(96,title.length());
            if(end>0 && Character.isHighSurrogate(title.charAt(end-1)))end--;
            question.append("\n").append(title,0,end).append("（视频").append(Objects.toString(row.get("videoId"),"")).append("）");
        }
        question.append("\n请回复“视频ID”（例如“视频1001”），或补充更准确的标题、发布时间。选定后再继续分析。");
        return Optional.of(question.toString());
    }
}
