package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.runtime.control.*;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real-provider gate; synthetic user materials only, bounded calls and output. */
@EnabledIfEnvironmentVariable(named="AGENT_MEMORY_MODEL_IT",matches="true")
class MemoryModelQualityTest {
    @Test void realModelUnderstandsProfilesConfirmationAndTemporaryRequests()throws Exception{
        var json=new ObjectMapper();var name=Objects.requireNonNull(System.getenv("AGENT_MEMORY_MODEL_NAME"));
        var config=new DeepSeekProperties(URI.create(System.getenv("AGENT_MEMORY_MODEL_BASE_URL")),System.getenv("AGENT_MEMORY_MODEL_KEY"),name,name,Duration.ofSeconds(3),384,3072,name,false);
        var evidence=new ArrayList<Map<String,Object>>();
        try(var provider=SdkTracerProvider.builder().build()){
            var model=new DeepSeekMemoryModel(config,json,ContextPolicy.defaults(),provider.get("memory-quality"));
            var school=new MemoryConsolidation.Source("u1","r1","s","USER",1,1,"h1","我是天津大学的。");
            var fruit=new MemoryConsolidation.Source("u2","r2","s","USER",3,2,"h2","我一直喜欢草莓。");
            var temporary=new MemoryConsolidation.Source("u3","r3","s","USER",5,3,"h3","这次回答详细一点。");
            var cases=List.of(List.of(school,fruit,temporary),List.of(school,new MemoryConsolidation.Source("save","r4","s","USER",7,4,"h4","存")),
                List.of(new MemoryConsolidation.Source("ask","r5","s","USER",1,1,"h1","今天聊聊天吧。"),new MemoryConsolidation.Source("guess","r5","s","ASSISTANT",2,1,"h2","你可能喜欢草莓。"),new MemoryConsolidation.Source("confirm","r6","s","USER",3,2,"h3","先不存，你猜的。")));
            for(int i=0;i<cases.size();i++){
                var sources=cases.get(i);var ids=i==0?List.of("u1","u2","u3"):List.of(i==1?"save":"confirm");
                var span=provider.get("memory-quality").spanBuilder("acceptance.memory.extract").startSpan();long start=System.nanoTime();
                try(var active=span.makeCurrent()){
                    var result=model.evaluate(MemoryConsolidation.PROMPT,Map.of("new_message_id",ids.get(ids.size()-1),"new_message_ids",ids,"messages",sources,"policy",MemoryConsolidation.VERSION),3072,
                        new CallControl(Instant.now().plusSeconds(30),new CancellationToken(),new ModelBudget(16384,4096),()->{}));
                    var parsed=MemoryConsolidation.parse(result,sources,ids);
                    if(i==0){assertTrue(parsed.candidates().stream().anyMatch(c->c.kind().equals("PROFILE") && c.text().contains("天津大学")));assertTrue(parsed.candidates().stream().anyMatch(c->c.text().contains("草莓")));assertFalse(parsed.candidates().stream().anyMatch(c->c.attribute().equals("response_length")));assertFalse(parsed.candidates().stream().anyMatch(c->c.text().matches(".*(学生|教师|本科|硕士|博士).*")));}
                    if(i==1){assertTrue(parsed.candidates().stream().anyMatch(c->c.kind().equals("PROFILE") && c.text().contains("天津大学")));assertTrue(parsed.candidates().stream().allMatch(c->c.support().size()>=2));assertFalse(parsed.candidates().stream().anyMatch(c->c.text().contains("学生")));}
                    if(i==2)assertTrue(parsed.candidates().isEmpty());
                    evidence.add(Map.of("case",i+1,"model",name,"traceId",span.getSpanContext().getTraceId(),"elapsedMillis",(System.nanoTime()-start)/1000000,"proposal",result));
                }finally{span.end();}
            }
        }finally{Files.writeString(Path.of("target/memory-real-evaluation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("syntheticMaterials",true,"cases",evidence)));}
    }
}
