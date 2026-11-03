package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.query.VideoSearchPort;
import com.tianmu.agent.tool.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public final class SearchMyVideosTool implements AgentTool {
    private final ObjectProvider<VideoSearchPort> sources;
    public SearchMyVideosTool(ObjectProvider<VideoSearchPort> sources){this.sources=sources;}
    public String name(){return "search_my_videos";}
    public ToolResult execute(ToolCall call,ToolContext context){
        var source=sources==null?null:sources.getIfAvailable();if(source==null)throw new AgentException("BUSINESS_UNAVAILABLE","Video discovery unavailable");
        String query=(String)call.arguments().getOrDefault("query","");
        final VideoSearchPort.Search request;
        try{request=new VideoSearchPort.Search(query,call.arguments().containsKey("start_date")?LocalDate.parse(ToolArguments.text(call,"start_date")):null,
                call.arguments().containsKey("end_date")?LocalDate.parse(ToolArguments.text(call,"end_date")):null,
                call.arguments().containsKey("offset")?Math.toIntExact(ToolArguments.integer(call,"offset")):0,
                call.arguments().containsKey("limit")?Math.toIntExact(ToolArguments.integer(call,"limit")):10);}
        catch(IllegalArgumentException|java.time.DateTimeException invalid){throw new AgentException("INVALID_ARGUMENTS","Supply an ordered publication date pair and valid pagination");}
        var page=context.query(c->source.search(request,context.scope(),c));
        var data=new LinkedHashMap<String,Object>();data.put("query",request.query());data.put("items",page.items());
        data.put("next_offset",page.nextOffset());data.put("complete",!page.hasMore());data.put("candidate_only",true);
        data.put("note","Author-owned metadata only. Multiple matches require clarification; selection does not change this run's frozen permissions.");
        var ids=page.items().stream().map(v->Long.parseLong(v.videoId())).collect(java.util.stream.Collectors.toSet());
        var facts=new LookupFacts(name(),data,ids);var evidence=context.registerLookup(facts);return new ToolResult(name(),facts.data(),evidence.id());
    }
}
