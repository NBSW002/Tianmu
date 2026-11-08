package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.feedback.FeedbackVectorIndex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.time.Duration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.feedback.milvus.enabled",havingValue="true")
@EnableConfigurationProperties(MilvusConfiguration.Properties.class)
public class MilvusConfiguration {
    @ConfigurationProperties("agent.feedback.milvus")
    public record Properties(String endpoint,String tokenFile,@DefaultValue("default") String database,
                             String collection,int dimension,@DefaultValue("5s") Duration timeout,
                             @DefaultValue("false") boolean provenanceEnabled) {}

    @Bean FeedbackVectorIndex feedbackVectorIndex(Properties p,ObjectMapper json){
        if(p.tokenFile()==null || p.tokenFile().isBlank())throw new IllegalArgumentException("Configure Milvus token-file");
        String token;
        try(var in=Files.newInputStream(Path.of(p.tokenFile()))){
            byte[] bytes=in.readNBytes(8193);
            if(bytes.length>8192)throw new IllegalArgumentException("Milvus token file exceeds limit");
            token=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).strip();
        }catch(java.io.IOException e){throw new IllegalArgumentException("Milvus token file is unavailable");}
        if(token.isBlank())throw new IllegalArgumentException("Milvus token file is empty");
        return new MilvusFeedbackVectorIndex(p.endpoint(),token,p.database(),p.collection(),p.dimension(),p.timeout(),json,p.provenanceEnabled());
    }
}
