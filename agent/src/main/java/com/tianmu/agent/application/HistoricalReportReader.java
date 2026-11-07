package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.store.*;
import java.util.*;

/** Reads the source of one saved, owner-authorized artifact. Never reruns analysis or treats it as fresh evidence. */
public final class HistoricalReportReader {
    private final RunRepository runs;private final ReportStore reports;private final FeedbackAnalysisService feedback;
    public HistoricalReportReader(RunRepository runs,ReportStore reports,FeedbackAnalysisService feedback){this.runs=runs;this.reports=reports;this.feedback=feedback;}
    public record Source(RunState run,ReportStore.Description report,Evidence evidence,Set<Long> videoIds,AccessScope authorized){}
    public Source source(UUID run,String artifact,AccessScope caller,TaskTypes.Specification task){
        if(runs==null || reports==null || !artifact.matches("rp_[0-9a-f-]{36}"))throw new AgentException("RESULT_NOT_FOUND","Report not found");
        var saved=runs.load(run,caller);var report=reports.describeReport(run,caller,artifact);
        var evidence=saved.execution().evidence().get(report.evidenceId());
        if(evidence==null || !evidence.runId().equals(run) || !evidence.owner().tenantId().equals(caller.tenantId()) || !evidence.owner().userId().equals(caller.userId()))throw new AgentException("RESULT_NOT_FOUND","Report source unavailable");
        long video=evidence.feedback()!=null?evidence.feedback().request().query().videoId():evidence.videoContent()!=null?evidence.videoContent().videoId():evidence.analysis()!=null?evidence.analysis().query().videoId():0;
        if(video==0 || !caller.videoIds().contains(video))throw new AgentException("FORBIDDEN","Report video is outside current authorization");
        Long frozen=task!=null && task.conditions().get("video_id") instanceof Number n?n.longValue():null;
        if(frozen!=null && frozen!=video)throw new AgentException("TASK_CONSTRAINT_VIOLATION","Historical report is for another video");
        if(task!=null && task.analysis()!=null){
            var sources=EnumSet.noneOf(AnalysisContract.Source.class);
            if(evidence.feedback()!=null){evidence.feedback().request().sources().forEach(s->sources.add(AnalysisContract.Source.valueOf(s.name())));if(evidence.feedback().request().grounding()==FeedbackAnalysis.Grounding.VIDEO_GROUNDED)sources.add(AnalysisContract.Source.VIDEO);}
            else if(evidence.videoContent()!=null)sources.add(AnalysisContract.Source.VIDEO);
            else if(evidence.analysis().sources()!=null)sources.addAll(evidence.analysis().sources().selectedSources());else sources.add(AnalysisContract.Source.DANMAKU);
            if(!task.analysis().sources().containsAll(sources))throw new AgentException("TASK_CONSTRAINT_VIOLATION","Report contains a source not selected for this task");
            var range=evidence.feedback()!=null?evidence.feedback().request().query():evidence.analysis()!=null?evidence.analysis().query():null;
            var expected=task.typed() instanceof TaskConditions.Report selectedReport?selectedReport.query():task.typed() instanceof TaskConditions.Interaction interaction?interaction.feedback():null;
            if(range!=null && expected!=null && !range.equals(expected))throw new AgentException("TASK_CONSTRAINT_VIOLATION","Report is outside the frozen feedback interval");
            if(evidence.videoContent()!=null && task.typed() instanceof TaskConditions.Video selected)selected.check(video,evidence.videoContent().startMs(),evidence.videoContent().endMs());
        }
        return new Source(saved,report,evidence,Set.of(video),caller);
    }
    public Map<String,Object> read(Source source,String artifact,String section,int offset,int limit){
        if(offset<0 || limit<1 || limit>10)throw new AgentException("INVALID_ARGUMENTS","Invalid report page");
        var evidence=source.evidence();var metadata=new LinkedHashMap<String,Object>();
        metadata.put("source_run_id",source.run().id.toString());metadata.put("artifact_id",artifact);metadata.put("generated_at",source.report().createdAt());
        metadata.put("source_run_status",source.run().snapshot().status());metadata.put("provenance",source.report().provenance());
        metadata.put("historical",true);metadata.put("freshness","SOURCE_SNAPSHOT_ONLY");metadata.put("video_ids",source.videoIds());
        Object content;
        if(evidence.feedback()!=null){
            var asset=feedback.historicalAsset(evidence.feedback().jobId(),source.authorized());
            metadata.put("data_as_of",asset.snapshot().dataAsOf());metadata.put("request",asset.request());
            if(com.tianmu.agent.feedback.FeedbackCompression.FIVE_VERSION.equals(asset.request().rulesVersion())){var summary=feedback.read(asset,"summary",0,1);metadata.put("assigned_counts",summary.get("assigned_counts"));metadata.put("method",summary.get("method"));metadata.put("limitations",summary.get("limitations"));}
            else metadata.put("counts",asset.snapshot().counts());
            content=section.equals("summary")?Map.of("model",asset.model(),"policy_hash",asset.policyHash()):section.equals("observations")?feedback.notes(asset,offset,limit):feedback.read(asset,"topics",offset,limit);
        }else if(evidence.videoContent()!=null){
            var v=evidence.videoContent();metadata.put("data_as_of",v.analyzedAt());metadata.put("coverage",v.coverage());metadata.put("playback_start_ms",v.startMs());metadata.put("playback_end_ms",v.endMs());
            content=section.equals("summary")?Map.of("summary",v.summary(),"model",v.model()):page(v.moments().stream().map(m->Map.<String,Object>of("at_ms",m.atMs(),"observation",m.observation())).toList(),offset,limit);
        }else{
            var data=evidence.analysis();metadata.put("data_as_of",data.dataAsOf());metadata.put("query",data.query());metadata.put("total_records",data.totalMessages());
            var checkpoint=source.run().execution().evidence().snapshot().values().stream().filter(e->e.reportCheckpoint()!=null && e.reportCheckpoint().evidenceId().equals(evidence.id()))
                    .map(e->e.reportCheckpoint().classification()).max(Comparator.comparingInt(DanmakuClassification::offset).thenComparing(DanmakuClassification::merged));
            if(checkpoint.isEmpty())throw new AgentException("REPORT_SOURCE_UNAVAILABLE","Historical report lacks structured topic provenance");
            content=section.equals("summary")?Map.of("topic_count",checkpoint.get().topics().size()):page(checkpoint.get().topics().stream().map(t->Map.<String,Object>of("title",t.title(),"observation",t.observation(),"recommendation",t.recommendation(),"messages",t.messages(),"video_context",t.videoContext())).toList(),offset,limit);
        }
        metadata.put("content",content);return metadata;
    }
    private static Map<String,Object> page(List<Map<String,Object>> rows,int offset,int limit){
        int from=Math.min(offset,rows.size()),to=Math.min(rows.size(),from+Math.min(limit,1));
        return Map.of("topics",rows.subList(from,to),"next_offset",to,"complete",to==rows.size());
    }
}
