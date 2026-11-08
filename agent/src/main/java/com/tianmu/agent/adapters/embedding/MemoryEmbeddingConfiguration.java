package com.tianmu.agent.adapters.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.memory.MemoryEmbedding;
import com.tianmu.agent.runtime.control.CallControl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.*;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.memory.embedding.enabled",havingValue="true")
@EnableConfigurationProperties(MemoryEmbeddingConfiguration.Properties.class)
public class MemoryEmbeddingConfiguration {
    @ConfigurationProperties("agent.memory.embedding")
    public record Properties(String endpoint,String tokenFile,@DefaultValue("qwen3.7-text-embedding") String model,String revision,@DefaultValue("2560") int dimension){}
    @Bean MemoryEmbedding memoryEmbedding(Properties p,ObjectMapper json){
        if(p.tokenFile()==null || p.tokenFile().isBlank())throw new IllegalArgumentException("Configure memory embedding token-file");
        String token;try(var in=Files.newInputStream(Path.of(p.tokenFile()))){var bytes=in.readNBytes(8193);if(bytes.length>8192)throw new IllegalArgumentException("Memory embedding token exceeds limit");token=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).strip();}
        catch(java.io.IOException failure){throw new IllegalArgumentException("Memory embedding token file unavailable");}
        var client=new QwenFeedbackEmbedding(p.endpoint(),token,p.model(),p.revision(),p.dimension(),json);
        return new MemoryEmbedding(){public String version(){return "memory-v1:"+client.version();}public int dimension(){return client.dimension();}public List<List<Float>> embed(List<String> texts,CallControl c){return client.embed(texts,c);}};
    }
}
