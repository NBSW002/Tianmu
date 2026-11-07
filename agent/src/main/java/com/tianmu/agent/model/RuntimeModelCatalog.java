package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.prompt.PromptCatalog;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Atomically publishes validated catalogs. Existing tasks retain immutable model instances. */
@Component @Profile("deepseek")
public final class RuntimeModelCatalog {
    public record Entry(String id,String label,String baseUrl,String model,String keyEnv,int maxTokens,boolean enabled) {}
    public record Document(String defaultModel,List<Entry> models) {}
    public record Choice(String id,String label,String model,String revision) {}
    public record Selection(Choice choice,ModelPort model) {}
    private record Catalog(String defaultId,Map<String,Selection> models,String revision) {}
    private volatile Catalog current;
    private final Map<Choice,Selection> revisions=new LinkedHashMap<>();
    private final Path path;
    private final java.util.function.Function<String,String> environment;
    private final ObjectMapper json;
    private final ContextPolicy policy;
    private final PromptCatalog prompts;
    private final io.opentelemetry.api.trace.Tracer tracer;
    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeModelCatalog(DeepSeekProperties defaults,ObjectMapper json,ContextPolicy policy,PromptCatalog prompts,
                               io.opentelemetry.api.trace.Tracer tracer,@Value("${agent.models.catalog:}") String file) {
        this(defaults,json,policy,prompts,tracer,file,System::getenv);
    }
    RuntimeModelCatalog(DeepSeekProperties defaults,ObjectMapper json,ContextPolicy policy,PromptCatalog prompts,
                        io.opentelemetry.api.trace.Tracer tracer,String file,java.util.function.Function<String,String> environment){
        this.environment=environment;this.json=json;this.policy=policy;this.prompts=prompts;this.tracer=tracer;this.path=file.isBlank()?null:Path.of(file);
        String startupRevision;try{startupRevision=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(Map.of("url",defaults.baseUrl().toString(),"key",defaults.apiKey(),"model",defaults.harnessModel(),"maxTokens",defaults.harnessMaxTokens()))));}catch(Exception error){throw new IllegalStateException("Cannot fingerprint model configuration");}
        var choice=new Choice("default",defaults.harnessModel(),defaults.harnessModel(),startupRevision);
        current=new Catalog("default",Map.of("default",new Selection(choice,build(defaults))),startupRevision);
        if(path!=null)reload();
    }
    private ModelPort build(DeepSeekProperties config) {return new DeepSeekHarnessModel(new DeepSeekClient(config,json,policy,tracer),config,json,prompts);}
    public synchronized void reload() {
        if(path==null)return;
        try {
            if(Files.size(path)>65536)throw new IllegalArgumentException();
            byte[] bytes=Files.readAllBytes(path);
            String revision=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if(revision.equals(current.revision()))return;
            var doc=json.readValue(bytes,Document.class);
            if(doc.models()==null || doc.models().isEmpty() || doc.models().size()>32)throw new IllegalArgumentException();
            Map<String,Selection> entries=new LinkedHashMap<>();Set<String> ids=new HashSet<>();
            for(var entry:doc.models()) {
                if(entry.id()==null || !entry.id().matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(entry.id()) || entry.label()==null || entry.label().isBlank() || entry.label().length()>80)throw new IllegalArgumentException();
                if(!entry.enabled())continue;
                if(entry.keyEnv()==null || !entry.keyEnv().matches("[A-Z][A-Z0-9_]{0,100}"))throw new IllegalArgumentException();
                var config=new DeepSeekProperties(URI.create(entry.baseUrl()),environment.apply(entry.keyEnv()),entry.model(),entry.model(),Duration.ofSeconds(3),384,entry.maxTokens(),entry.model());
                var choice=new Choice(entry.id(),entry.label(),entry.model(),revision);
                entries.put(entry.id(),new Selection(choice,build(config)));
            }
            if(!entries.containsKey(doc.defaultModel()))throw new IllegalArgumentException();
            current.models().values().forEach(value->revisions.put(value.choice(),value));
            while(revisions.size()>128)revisions.remove(revisions.keySet().iterator().next());
            current=new Catalog(doc.defaultModel(),Collections.unmodifiableMap(entries),revision);
        } catch(Exception invalid){throw new AgentException("MODEL_CATALOG_INVALID","Model catalog update is invalid; previous configuration retained");}
    }
    public synchronized Selection restore(Choice choice){
        var local=current.models().get(choice.id());if(local!=null && local.choice().equals(choice))return local;
        var prior=revisions.get(choice);if(prior!=null)return prior;
        throw new AgentException("MODEL_REVISION_UNAVAILABLE","Pinned catalog revision unavailable after restart; start a new turn");
    }
    public List<Choice> choices(){reload();return current.models().values().stream().map(Selection::choice).toList();}
    public Selection select(String id){reload();var snapshot=current;var result=snapshot.models().get(id==null||id.isBlank()?snapshot.defaultId():id);if(result==null)throw new AgentException("MODEL_NOT_AVAILABLE","Requested model is disabled or unknown");return result;}
}
