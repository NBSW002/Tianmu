package com.tianmu.agent.preparation;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.ToolCatalog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IntentCandidateCatalogTest {
    private final IntentCandidateCatalog catalog=new IntentCandidateCatalog(new SkillCatalog(),new ToolCatalog());
    private final AccessScope author=new AccessScope("tenant","author",Set.of(1001L));
    private Input input(AccessScope scope){return new Input("分析这个视频的评论",LocalDate.of(2026,11,6),scope,
            com.tianmu.agent.core.MemoryTypes.View.empty(),java.time.Instant.now(),catalog.candidates(scope));}
    @Test void candidatesFollowCreatorGrantsWithoutRequiringTaskParameters() {
        var authorChoices=catalog.candidates(author);assertEquals(12,authorChoices.size());
        assertTrue(authorChoices.stream().anyMatch(c->c.id().equals("comment-analysis")));
        var viewer=new AccessScope("tenant","viewer",Set.of(),"",Set.of(1001L));var choices=catalog.candidates(viewer);
        assertTrue(choices.stream().anyMatch(c->c.id().equals("video-content")));
        assertTrue(choices.stream().noneMatch(c->Set.of("comment-analysis","audience-report","video-metrics","smart-comment").contains(c.id())));
        assertThrows(UnsupportedOperationException.class,()->choices.clear());
    }
    @Test void selectionUsesOnlyProvidedCandidatesAndServerDerivedCategory() {
        var input=input(author);var selection=IntentCandidateCatalog.validate(input,new IntentRecognition(Intent.OTHER,"audience-report",List.of("video-content","comment-analysis"),List.of()));
        assertEquals(Intent.DANMAKU_REPORT,selection.intent());assertEquals(input.intentCandidates(),selection.candidates());
        assertThrows(IllegalArgumentException.class,()->IntentCandidateCatalog.validate(input,new IntentRecognition(Intent.OTHER,"run_shell",List.of(),List.of())));
        var viewer=new AccessScope("tenant","viewer",Set.of(),"",Set.of(1001L));
        assertThrows(IllegalArgumentException.class,()->IntentCandidateCatalog.validate(input(viewer),new IntentRecognition(Intent.OTHER,"smart-comment",List.of(),List.of())));
        assertThrows(IllegalArgumentException.class,()->IntentCandidateCatalog.validate(input,new IntentRecognition(Intent.OTHER,"conversation",List.of("smart-unknown"),List.of())));
        assertThrows(IllegalArgumentException.class,()->new IntentRecognition(Intent.OTHER,"conversation",List.of("knowledge","knowledge"),List.of()));
    }
    @TempDir Path directory;
    @Test void newlyRegisteredSkillBecomesACandidateAndMissingToolsDoNot() throws Exception {
        var metadata=directory.resolve("skill.json");Files.writeString(metadata,"""
                {"name":"custom-advice","version":"1.0.0","description":"自定义建议能力","tools":["custom_tool"]}
                """);Files.writeString(directory.resolve("SKILL.md"),"Provide bounded advice.");
        var tool=directory.resolve("tool.json");Files.writeString(tool,"""
                {"name":"custom_tool","description":"advice","inputSchema":{"type":"object","properties":{},"required":[],"additionalProperties":false}}
                """);
        var skills=new SkillCatalog(new Resource[]{new FileSystemResource(metadata)});
        var tools=new ToolCatalog(new Resource[]{new FileSystemResource(tool)});
        assertTrue(new IntentCandidateCatalog(skills,tools).candidates(author).stream().anyMatch(c->c.id().equals("custom-advice")));
        assertFalse(new IntentCandidateCatalog(skills,new ToolCatalog()).candidates(author).stream().anyMatch(c->c.id().equals("custom-advice")));
    }
}
