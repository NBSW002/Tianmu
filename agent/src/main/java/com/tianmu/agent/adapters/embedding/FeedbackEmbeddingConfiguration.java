package com.tianmu.agent.adapters.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.feedback.FeedbackEmbedding;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.*;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.*;
import java.nio.file.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.feedback.embedding.enabled",havingValue="true")
@EnableConfigurationProperties(FeedbackEmbeddingConfiguration.Properties.class)
public class FeedbackEmbeddingConfiguration {
    @ConfigurationProperties("agent.feedback.embedding")
    public record Properties(String endpoint,String tokenFile,@DefaultValue("qwen3.7-text-embedding") String model,String revision,@DefaultValue("2560") int dimension) {}
    @Bean FeedbackEmbedding feedbackEmbedding(Properties p,ObjectMapper json){
        String token;if(p.tokenFile()==null || p.tokenFile().isBlank())throw new IllegalArgumentException("Configure embedding token-file");
        try(var in=Files.newInputStream(Path.of(p.tokenFile()))){var bytes=in.readNBytes(8193);if(bytes.length>8192)throw new IllegalArgumentException("Embedding token file exceeds limit");token=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).strip();}
        catch(java.io.IOException failure){throw new IllegalArgumentException("Embedding token file unavailable");}
        return new QwenFeedbackEmbedding(p.endpoint(),token,p.model(),p.revision(),p.dimension(),json);
    }
}
