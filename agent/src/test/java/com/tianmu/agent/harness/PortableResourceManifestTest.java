package com.tianmu.agent.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.application.TaskAssembler;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.core.TaskTypes.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.resource.*;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.ToolCatalog;
import org.springframework.core.io.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PortableResourceManifestTest {
    static class TextResource extends AbstractResource {
        final Resource source;final boolean crlf,changed;
        TextResource(Resource source,boolean crlf,boolean changed){this.source=source;this.crlf=crlf;this.changed=changed;}
        public String getDescription(){return source.getDescription();}
        public String getFilename(){return source.getFilename();}
        public Resource createRelative(String path)throws IOException{return new TextResource(source.createRelative(path),crlf,changed);}
        public InputStream getInputStream()throws IOException {
            String value;try(var in=source.getInputStream()){value=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n");}
            if(changed && getFilename().endsWith(".md"))value+="\nChanged resource content";
            if(crlf)value=value.replace("\n","\r\n");
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }
    }
    SkillCatalog skills(boolean crlf,boolean changed){return new SkillCatalog(Arrays.stream(ResourceFiles.discover("classpath*:skills/*/skill.json")).map(r->new TextResource(r,crlf,changed)).toArray(Resource[]::new));}
    PromptCatalog prompts(boolean crlf){return new PromptCatalog(new DefaultResourceLoader(){@Override public Resource getResource(String path){return new TextResource(super.getResource(path),crlf,false);}});}
    @Test void bothCheckoutStylesReadTheRealPriorManifestAndChangedContentStillFails()throws Exception {
        var json=new ObjectMapper();var fixture=json.readTree(getClass().getResourceAsStream("/compat/agent-59a1746-resource-manifest.json"));
        Map<String,String> previous=json.convertValue(fixture.get("resourceVersions"),new TypeReference<Map<String,String>>(){});
        Set<String> tools=json.convertValue(fixture.get("allowedTools"),new TypeReference<Set<String>>(){});
        var lf=skills(false,false);var windows=skills(true,false);
        var linux=new TaskAssembler(lf,prompts(false),new ToolCatalog());var win=new TaskAssembler(windows,prompts(true),new ToolCatalog());
        assertEquals(linux.resourceVersions(),win.resourceVersions(),"New policy fingerprints must be independent of checkout line endings");
        var spec=new Specification("conversation",Map.of(),Map.of());var scope=new AccessScope("test","alice",Set.of(1001L));
        var task=new PreparedTask(UUID.randomUUID(),"question","question",LocalDate.now(),"Asia/Shanghai",scope,new PreparedRequest("question","question",Emotion.UNKNOWN,Set.of(),Set.of(),Intent.KNOWLEDGE,spec),spec,previous,tools,Instant.now().plusSeconds(30),16);
        assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->linux.verify(task)).code());assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->win.verify(task)).code());
        var current=new PreparedTask(task.runId(),task.originalQuery(),task.query(),task.businessDate(),task.timezone(),scope,task.preparation(),spec,linux.resourceVersions(),new ToolCatalog().all().keySet(),task.deadline(),16);
        assertDoesNotThrow(()->linux.verify(current));assertDoesNotThrow(()->win.verify(current));
        assertFalse(ResourceManifestCompatibility.skill("audience-report",previous.get("skill-content:audience-report"),null,lf.fingerprint("audience-report"),lf.canonicalFingerprint("audience-report")),"Changed report instructions must not reuse the legacy alias");
        var altered=new TaskAssembler(skills(false,true),prompts(false),new ToolCatalog());
        assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->altered.verify(task)).code());
        for(String name:lf.names())assertTrue(ResourceManifestCompatibility.skill(name,linux.resourceVersions().get("skill-content:"+name),"2",lf.fingerprint(name),lf.canonicalFingerprint(name)),name);
    }
}
