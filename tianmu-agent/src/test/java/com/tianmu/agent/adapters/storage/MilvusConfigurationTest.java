package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.feedback.FeedbackVectorIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class MilvusConfigurationTest {
    @TempDir Path directory;
    final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(MilvusConfiguration.class).withBean(ObjectMapper.class,ObjectMapper::new);

    @Test void disabledByDefaultDoesNotRequireCredentialsOrNetwork(){
        context.run(c->assertThat(c).hasNotFailed().doesNotHaveBean(FeedbackVectorIndex.class));
    }
    @Test void explicitEnableRequiresSecretFile(){
        context.withPropertyValues("agent.feedback.milvus.enabled=true")
                .run(c->assertThat(c).hasFailed());
    }
    @Test void validConfigurationCreatesAdapterWithoutContactingServer() throws Exception {
        var token=directory.resolve("milvus-token");Files.writeString(token,"test-only-secret\n");
        configured(token).run(c->assertThat(c).hasNotFailed().hasSingleBean(FeedbackVectorIndex.class));
    }
    @Test void missingEmptyAndOversizedSecretFailClosed() throws Exception {
        var token=directory.resolve("milvus-token");
        configured(token).run(c->assertThat(c).hasFailed());
        Files.writeString(token,"\n");configured(token).run(c->assertThat(c).hasFailed());
        Files.writeString(token,"x".repeat(8193));configured(token).run(c->assertThat(c).hasFailed());
    }
    @Test void dimensionAndTimeoutMustBeExplicitlyValid() throws Exception {
        var token=directory.resolve("milvus-token");Files.writeString(token,"test-only-secret");
        configured(token).withPropertyValues("agent.feedback.milvus.dimension=0")
                .run(c->assertThat(c).hasFailed());
        configured(token).withPropertyValues("agent.feedback.milvus.timeout=60001ms")
                .run(c->assertThat(c).hasFailed());
    }
    private ApplicationContextRunner configured(Path token){
        return context.withPropertyValues("agent.feedback.milvus.enabled=true",
                "agent.feedback.milvus.endpoint=http://127.0.0.1:1",
                "agent.feedback.milvus.collection=feedback_test", "agent.feedback.milvus.dimension=3",
                "agent.feedback.milvus.token-file="+token);
    }
}
