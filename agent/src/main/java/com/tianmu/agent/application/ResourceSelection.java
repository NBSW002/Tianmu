package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentTypes.ToolResult;
import com.tianmu.agent.core.AgentTypes.ModelContext;
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
    public static String requiredTool(ModelContext context){
        var task=context.task()==null?null:context.task().specification();
        if(!requiresEvidence(task,context.observations()))return null;
        if(!Boolean.TRUE.equals(task.conditions().get("report_reference_required")))return "required";
        var names=context.availableTools().stream().map(t->t.name()).collect(java.util.stream.Collectors.toSet());
        if(!names.contains("read_report"))return names.contains("read_skill")?"read_skill":"required";
        String references=context.task().originalQuery()+"\n"+context.memory().recent().stream().map(m->m.text()).collect(java.util.stream.Collectors.joining("\n"))
                +"\n"+context.observations().stream().map(r->r.data().toString()).collect(java.util.stream.Collectors.joining("\n"));
        if(java.util.regex.Pattern.compile("/api/agent/runs/[0-9a-f-]{36}/reports/rp_[0-9a-f-]{36}").matcher(references).find())return "read_report";
        boolean exhausted=context.observations().stream().anyMatch(r->"read_history".equals(r.tool()) && Boolean.FALSE.equals(r.data().get("more_available")));
        return exhausted && names.contains("ask_clarification")?"ask_clarification":"read_history";
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
