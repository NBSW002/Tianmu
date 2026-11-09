package com.tianmu.agent.harness.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;

/** Request-scoped metadata. Prompt bodies stay in authorized, bounded detail storage. */
public final class TracePrompt {
    private TracePrompt() {}
    private record Metadata(String id,String version,String variant,String instruction,List<String> components) {}
    private static final ContextKey<Metadata> KEY=ContextKey.named("agent.trace.prompt");
    public static <T>T call(String id,String version,String variant,String instruction,List<String> components,Supplier<T> action){
        var metadata=new Metadata(id,version,variant,instruction,List.copyOf(components));
        try(var scope=Context.current().with(KEY,metadata).makeCurrent()){attributes(metadata);return action.get();}
    }
    private static void attributes(Metadata m){TraceAttributes.apply(Span.current(),Map.of("prompt.id",m.id(),"prompt.version",m.version(),"prompt.variant",m.variant(),"prompt.hash",hash(m.instruction()),"prompt.components",m.components()));}
    public static void capture(List<? extends Map<String,?>> messages){
        var metadata=Context.current().get(KEY);
        var instructions=new ArrayList<Map<String,Object>>();
        for(int index=0;index<messages.size();index++){
            var message=messages.get(index);
            String role=Objects.toString(message.get("role"),"");
            if(Set.of("system","developer").contains(role))
                instructions.add(Map.of("index",index,"role",role,"content",Objects.toString(message.get("content"),"")));
        }
        var canonical=new StringBuilder();for(var instruction:instructions){String role=instruction.get("role").toString(),content=instruction.get("content").toString();canonical.append(role.length()).append(':').append(role).append(content.length()).append(':').append(content);}
        if(metadata==null)metadata=new Metadata("inline.unregistered","content-addressed","UNREGISTERED",canonical.toString(),List.of());
        attributes(metadata);
        var roles=messages.stream().map(m->Objects.toString(m.get("role"),"unknown")).toList();
        TraceAttributes.apply(Span.current(),Map.of("prompt.effective_hash",hash(canonical.toString()),
                "message.roles",roles.stream().distinct().limit(32).toList(),
                "message.ordered_roles",roles.stream().limit(32).toList()));
        TraceDetails.capture("prompt",Map.of("id",metadata.id(),"version",metadata.version(),"variant",metadata.variant(),"instruction",metadata.instruction(),"requestInstructions",instructions,"components",metadata.components()),"PROMPT_SNAPSHOT");
    }
    public static void inline(String id,String version,String variant,String instruction){var metadata=new Metadata(id,version,variant,instruction,List.of());attributes(metadata);TraceAttributes.apply(Span.current(),Map.of("prompt.effective_hash",hash(instruction)));TraceDetails.capture("prompt",Map.of("id",id,"version",version,"variant",variant,"instruction",instruction),"PROMPT_SNAPSHOT");}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
