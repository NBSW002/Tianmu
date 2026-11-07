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
@EnableConfigurationProperties({FeedbackPolicy.class,FeedbackReusePolicy.class,FeedbackRuntimeProperties.class})
public class FeedbackConfiguration {
    @Bean @ConditionalOnProperty(name="agent.storage.mode",havingValue="mysql")
    FeedbackPreparationStore mysqlFeedbackPreparation(com.zaxxer.hikari.HikariDataSource source,org.flywaydb.core.Flyway migrations,ObjectMapper json,com.tianmu.agent.runtime.store.RunRepository runs,FeedbackRuntimeProperties governance){return new com.tianmu.agent.adapters.storage.MysqlFeedbackPreparationStore(source,json,runs::verifyReady,governance);}
    @Bean @ConditionalOnMissingBean(FeedbackPreparationStore.class)
    FeedbackPreparationStore memoryFeedbackPreparation(){return new MemoryFeedbackPreparationStore();}
    @Bean FeedbackClusters feedbackClusters(ObjectProvider<FeedbackVectorIndex> vectors,ObjectProvider<FeedbackEmbedding> embedding){return new FeedbackClusters(vectors.getIfAvailable(),embedding.getIfAvailable());}
    @Bean FeedbackPreparationService feedbackPreparationService(FeedbackAnalysisService analysis,FeedbackPreparationStore store,FeedbackObjects objects,FeedbackPolicy policy,FeedbackClusters clusters,FeedbackRuntimeProperties governance){return new FeedbackPreparationService(analysis,store,objects,policy,clusters,governance);}
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
