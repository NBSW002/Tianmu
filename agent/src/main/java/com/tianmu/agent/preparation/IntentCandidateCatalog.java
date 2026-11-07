package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.access.AssistantToolAccess;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.ToolCatalog;
import org.springframework.stereotype.Component;
import java.util.*;

/** Capability choices describe goals; actual execution still uses task and tool admission. */
@Component
public final class IntentCandidateCatalog {
    private record Definition(String id,String label,String skill,String tool,Intent category,String boundary,List<String> conditions) {}
    private static final List<Definition> DEFINITIONS=List.of(
        new Definition("video-content","视频内容理解","video-content","analyze_video_content",Intent.OTHER,"理解画面、镜头或片段；不自动读取弹幕、评论。",List.of("视频对象")),
        new Definition("video-metrics","视频互动统计","video-metrics","query_video_metrics",Intent.VIDEO_METRIC,"查询数量指标；区分内容分析和生成报告。",List.of("视频对象","查询日期或范围")),
        new Definition("danmaku-analysis","弹幕分析","danmaku-analysis","analyze_danmaku",Intent.DANMAKU_REPORT,"目标是分析弹幕内容；单纯查数量选互动统计。",List.of("视频对象")),
        new Definition("comment-analysis","评论分析","comment-analysis","analyze_comments",Intent.DANMAKU_REPORT,"目标是分析评论；不自行增加弹幕来源。",List.of("视频对象")),
        new Definition("audience-report","观众反馈报告","audience-report","create_feedback_report",Intent.DANMAKU_REPORT,"以生成报告为主目标；关联目标仅保留用户要求的来源。",List.of("视频对象","读取来源")),
        new Definition("video-discovery","查找自己的视频","resource-discovery","search_my_videos",Intent.OTHER,"查找当前用户的视频候选；不代表全站语义搜索或推荐。",List.of()),
        new Definition("report-query","历史报告查询","resource-discovery","read_report",Intent.OTHER,"查找或阅读已有私有报告；不自动重新分析。",List.of()),
        new Definition("smart-comment","智能评论草稿","smart-comment","prepare_comment_draft",Intent.OTHER,"生成作者评论草稿；发送仍需用户确认。",List.of("本人的视频")),
        new Definition("smart-danmaku","智能弹幕草稿","smart-danmaku","prepare_danmaku_draft",Intent.OTHER,"生成带播放位置的作者弹幕草稿；发送仍需用户确认。",List.of("本人的视频")));
    private final SkillCatalog skills;
    private final ToolCatalog tools;
    @org.springframework.beans.factory.annotation.Value("${agent.memory.enabled:false}") private boolean memoryEnabled;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.PersonalMemoryStore memories;
    public IntentCandidateCatalog(SkillCatalog skills,ToolCatalog tools) {this.skills=skills;this.tools=tools;}
    public List<IntentCandidate> candidates(AccessScope scope) {
        List<IntentCandidate> result=new ArrayList<>();
        result.add(new IntentCandidate("conversation","通用对话","闲聊、写作、整理想法。","无需读取实际业务数据。",null,Set.of(),List.of(),Intent.OTHER));
        result.add(new IntentCandidate("knowledge","知识与操作咨询","解释概念、介绍能力、使用指导。","询问功能本身不等于要求执行功能。",null,Set.of(),List.of(),Intent.KNOWLEDGE));
        Set<String> described=new HashSet<>();
        for(var definition:DEFINITIONS) {
            described.add(definition.skill());
            if(!skills.names().contains(definition.skill()) || !tools.all().containsKey(definition.tool())
                    || !skills.read(definition.skill()).tools().contains(definition.tool()) || !AssistantToolAccess.permits(definition.tool(),scope))continue;
            var skill=skills.read(definition.skill());
            var allowed=skill.tools().stream().filter(tools.all()::containsKey).filter(tool->AssistantToolAccess.permits(tool,scope)).collect(java.util.stream.Collectors.toSet());
            result.add(new IntentCandidate(definition.id(),definition.label(),skill.description(),definition.boundary(),skill.name(),allowed,definition.conditions(),definition.category()));
        }
        for(String name:new TreeSet<>(skills.names())) {
            if(name.equals("personal-memory") && !memoryEnabled)continue;
            if(described.contains(name))continue;
            var skill=skills.read(name);
            if(!tools.all().keySet().containsAll(skill.tools()) || skill.tools().stream().anyMatch(tool->!AssistantToolAccess.permits(tool,scope)))continue;
            if(name.equals("personal-memory")){
                if(memories==null)continue;
                var mode=memories.settings(scope).mode();if(mode==com.tianmu.agent.memory.PersonalMemory.Mode.OFF)continue;
                var allowed=mode==com.tianmu.agent.memory.PersonalMemory.Mode.READ_ONLY?Set.of("search_memory","read_memory"):skill.tools();
                result.add(new IntentCandidate(name,"个人记忆",mode==com.tianmu.agent.memory.PersonalMemory.Mode.READ_ONLY?"查找和阅读已有记忆。":skill.description(),"保存、修改和遗忘必须由本轮明确授权；不根据隐含偏好写入。",name,allowed,List.of(),Intent.OTHER));continue;
            }
            result.add(new IntentCandidate(name,name,skill.description(),"按注册能力说明识别，不扩大读取或操作范围。",name,skill.tools(),List.of(),Intent.OTHER));
        }
        result.add(new IntentCandidate("unknown","尚未明确","无法可靠对应候选能力。","缺少视频或日期本身不代表目标不明确。",null,Set.of(),List.of(),Intent.UNKNOWN));
        if(result.size()>32)throw new IllegalStateException("Too many intent candidates");
        return List.copyOf(result);
    }
    public static IntentRecognition validate(Input input,IntentRecognition selection) {
        if(selection.primary()==null) {
            if(!selection.related().isEmpty())throw new IllegalArgumentException("Legacy selection cannot add related goals");
            return new IntentRecognition(selection.intent(),null,List.of(),input.intentCandidates());
        }
        var byId=new HashMap<String,IntentCandidate>();input.intentCandidates().forEach(candidate->byId.put(candidate.id(),candidate));
        var primary=byId.get(selection.primary());
        if(primary==null || selection.related().stream().anyMatch(id->!byId.containsKey(id) || id.equals("unknown"))
                || selection.primary().equals("unknown") && !selection.related().isEmpty())throw new IllegalArgumentException("Selection outside capability candidates");
        return new IntentRecognition(primary.category(),primary.id(),selection.related(),input.intentCandidates());
    }
}
