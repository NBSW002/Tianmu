package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.MysqlFeedbackJobStore;
import com.tianmu.agent.runtime.store.ObjectStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.nio.file.Path;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({FeedbackPolicy.class,FeedbackReusePolicy.class,FeedbackRuntimeProperties.class,FeedbackCompressionProperties.class,FeedbackBucketProperties.class,FeedbackRerankProperties.class,FeedbackTieredProperties.class})
public class FeedbackConfiguration {
    @Bean @ConditionalOnProperty(name="agent.storage.mode",havingValue="mysql")
    FeedbackPreparationStore mysqlFeedbackPreparation(com.zaxxer.hikari.HikariDataSource source,org.flywaydb.core.Flyway migrations,ObjectMapper json,com.tianmu.agent.runtime.store.RunRepository runs,FeedbackRuntimeProperties governance){return new com.tianmu.agent.adapters.storage.MysqlFeedbackPreparationStore(source,json,runs::verifyReady,governance);}
    @Bean @ConditionalOnMissingBean(FeedbackPreparationStore.class)
    FeedbackPreparationStore memoryFeedbackPreparation(){return new MemoryFeedbackPreparationStore();}
    @Bean FeedbackClusters feedbackClusters(ObjectProvider<FeedbackVectorIndex> vectors,ObjectProvider<FeedbackEmbedding> embedding){return new FeedbackClusters(vectors.getIfAvailable(),embedding.getIfAvailable());}
    @Bean FeedbackCompression feedbackCompression(ObjectProvider<FeedbackEmbedding> embedding,ObjectProvider<FeedbackVectorIndex> vectors,FeedbackCompressionProperties policy,FeedbackBucketProperties buckets,FeedbackTieredProperties tiered,ObjectMapper json){var compression=new FeedbackCompression(embedding.getIfAvailable(),vectors.getIfAvailable(),policy,json);compression.bucketPolicy(buckets);compression.tieredPolicy(tiered);return compression;}
    @Bean FeedbackPreparationService feedbackPreparationService(FeedbackAnalysisService analysis,FeedbackPreparationStore store,FeedbackObjects objects,FeedbackPolicy policy,FeedbackClusters clusters,FeedbackRuntimeProperties governance,FeedbackCompression compression,FeedbackRerankProperties rerank,FeedbackReusePolicy reuse,ObjectMapper json){
        String key=rerank.apiKey();
        if(rerank.apiKeyFile()!=null && !rerank.apiKeyFile().isBlank()){
            try(var stream=java.nio.file.Files.newInputStream(Path.of(rerank.apiKeyFile()))){
                var bytes=stream.readNBytes(8193);
                if(bytes.length>8192)throw new IllegalArgumentException("Rerank key file exceeds limit");
                key=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).strip();
            }catch(java.io.IOException unavailable){
                if(rerank.enabled())throw new IllegalArgumentException("Rerank key file unavailable",unavailable);
            }
        }
        compression.rerankProviderRevision(rerank.model(),rerank.endpoint());
        var preparation=new FeedbackPreparationService(analysis,store,objects,policy,clusters,governance,compression);
        var provider=rerank.endpoint()==null || rerank.endpoint().isBlank() || key==null || key.isBlank()?null
                :new com.tianmu.agent.adapters.embedding.QwenFeedbackReranker(rerank.endpoint(),key,rerank.model(),json);
        preparation.configureRerank(rerank.enabled(),provider,reuse);
        return preparation;
    }
    @Bean @ConditionalOnProperty(name="agent.storage.mode",havingValue="mysql")
    FeedbackJobStore mysqlFeedbackJobs(com.zaxxer.hikari.HikariDataSource source,org.flywaydb.core.Flyway migrations,ObjectMapper json,com.tianmu.agent.runtime.store.RunRepository runs,FeedbackRuntimeProperties governance){return new MysqlFeedbackJobStore(source,json,runs::verifyReady,governance);}
    @Bean @ConditionalOnMissingBean(FeedbackJobStore.class)
    FeedbackJobStore memoryFeedbackJobs(){return new MemoryFeedbackJobStore();}
    @Bean @ConditionalOnProperty(name="agent.storage.mode",havingValue="mysql")
    FeedbackObjectIndex mysqlFeedbackObjectIndex(com.zaxxer.hikari.HikariDataSource source,org.flywaydb.core.Flyway migrations,com.tianmu.agent.runtime.store.RunRepository runs,FeedbackRuntimeProperties governance){return new com.tianmu.agent.adapters.storage.MysqlFeedbackObjectIndex(source,runs::verifyReady,governance);}
    @Bean FeedbackObjects feedbackObjects(ObjectProvider<ObjectStore> objects,ObjectMapper json,ObjectProvider<FeedbackObjectIndex> index,FeedbackRuntimeProperties governance){
        return new FeedbackObjects(objects.getIfAvailable(()->new com.tianmu.agent.adapters.storage.FileObjectStore(Path.of("./var/feedback-demo"))),json,index.getIfAvailable(()->FeedbackObjectIndex.NONE),governance);
    }
    @Bean @Profile("!deepseek") FeedbackModels demoFeedbackModels(){return new DemoFeedbackModels();}
    @Bean FeedbackResultCache feedbackResultCache(ObjectMapper json,FeedbackReusePolicy policy,ObjectProvider<javax.sql.DataSource> source,FeedbackRuntimeProperties governance){return new FeedbackResultCache(json,policy,source.getIfAvailable(),governance);}
    @Bean FeedbackAnalysisService feedbackAnalysisService(FeedbackJobStore store,FeedbackObjects objects,FeedbackModels models,FeedbackPolicy policy,ObjectProvider<FeedbackSnapshotPort> sources,FeedbackResultCache cache,FeedbackRuntimeProperties governance){
        var service=new FeedbackAnalysisService(store,objects,sources.getIfAvailable(()->new FeedbackSnapshotPort(){
            public Receipt export(com.tianmu.agent.core.FeedbackAnalysis.Request r,com.tianmu.agent.core.AgentTypes.AccessScope o,com.tianmu.agent.runtime.control.CallControl c,java.util.function.Consumer<java.util.List<com.tianmu.agent.core.FeedbackAnalysis.Unit>> sink){throw new com.tianmu.agent.core.AgentException("FEEDBACK_SOURCE_UNAVAILABLE","No streaming snapshot source is configured");}
            public void authorize(com.tianmu.agent.core.AgentTypes.AccessScope o,long id){if(!o.videoIds().contains(id))throw new com.tianmu.agent.core.AgentException("FORBIDDEN","Video outside scope");}
        }),models,policy,governance);service.resultCache(cache);return service;
    }
}
