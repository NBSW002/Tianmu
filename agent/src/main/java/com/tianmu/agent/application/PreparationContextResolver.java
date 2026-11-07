package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.TaskTypes.Specification;
import com.tianmu.agent.preparation.VideoDiscovery;
import com.tianmu.agent.query.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Resolves immutable page/focus input. Only the application coordinator applies the returned projection. */
@Component
public final class PreparationContextResolver {
    public record Request(UUID runId,String originalQuery,String query,LocalDate businessDate,AccessScope owner,MemoryTypes.View memory,RequestContext page) {}
    public record Resolved(MemoryTypes.View memory,String analysisQuery,Specification lookup) {}
    public interface Stages { <T>T call(String name,Supplier<T> action); }
    private final VideoPagePort pages;
    private final VideoSearchPort searches;
    public PreparationContextResolver(VideoPagePort pages,VideoSearchPort searches){this.pages=pages;this.searches=searches;}
    @org.springframework.beans.factory.annotation.Autowired
    public PreparationContextResolver(org.springframework.beans.factory.ObjectProvider<VideoPagePort> pages,org.springframework.beans.factory.ObjectProvider<VideoSearchPort> searches) {
        this(pages.getIfAvailable(),searches.getIfAvailable());
    }
    public Resolved resolve(Request input,Supplier<CallControl> controls,Stages stages) {
        controls.get().checkpoint();var page=input.page();
        if(page!=null) {
            if(!input.owner().readableVideoIds().contains(Long.parseLong(page.videoId())))throw new AgentException("FORBIDDEN","Current page outside scope");
            if(pages!=null) {
                var metadata=pages.page(Long.parseLong(page.videoId()),input.owner());controls.get().checkpoint();
                if(metadata==null || !page.videoId().equals(metadata.videoId()))throw new AgentException("BUSINESS_UNAVAILABLE","Invalid page metadata");
                page=new RequestContext(page.videoId(),metadata.title(),page.playbackMs()!=null && page.playbackMs()<metadata.durationMs()?page.playbackMs():null);
            } else page=new RequestContext(page.videoId(),"",page.playbackMs());
        }
        var discovery=VideoDiscovery.request(input.query(),input.businessDate());
        if(discovery.isPresent()) {
            page=null;
            if(searches!=null) {
                var control=controls.get();
                var candidates=stages.call("agent.prepare.video_discovery",()->searches.search(discovery.get(),input.owner(),control));control.checkpoint();
                if(!candidates.hasMore() && candidates.items().size()==1) {
                    var match=candidates.items().get(0);int end=Math.min(160,match.title().length());
                    if(end>0 && Character.isHighSurrogate(match.title().charAt(end-1)))end--;
                    if(input.owner().readableVideoIds().contains(Long.parseLong(match.videoId())))page=new RequestContext(match.videoId(),match.title().substring(0,end),null);
                }
            }
        }
        var memory=input.memory();var prior=memory.focus();
        if(prior==null)prior=ConversationFocus.recover(memory.recent(),page);
        var focus=ConversationFocus.update(input.runId(),input.originalQuery(),page,prior);
        var projected=memory.focus()!=null && !Objects.equals(memory.focus().referenceVideoId(),focus.referenceVideoId())
                ?new MemoryTypes.View(memory.version(),"",List.of(),true,memory.boundary(),page,focus):memory.withContext(page,focus);
        String query=discovery.isPresent() && page!=null?VideoDiscovery.analysisText(input.query()):input.query();
        boolean lookup=discovery.isPresent() && page==null || VideoDiscovery.historicalReport(input.query()) && !DanmakuTarget.matcher(input.query()).find();
        var specification=lookup?new Specification("resource.lookup",Map.of("selection_required",discovery.isPresent() && page==null && VideoDiscovery.requiresSelection(input.query()),
                "report_reference_required",VideoDiscovery.historicalReport(input.query())),Map.of()):null;
        return new Resolved(projected,query,specification);
    }
}
