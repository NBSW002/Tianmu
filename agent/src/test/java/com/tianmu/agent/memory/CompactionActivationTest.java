package com.tianmu.agent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class CompactionActivationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withInitializer(context->{
                try {
                    for(var source:new YamlPropertySourceLoader().load("defaults",new ClassPathResource("application.yml")))
                        context.getEnvironment().getPropertySources().addLast(source);
                } catch(java.io.IOException error){throw new IllegalStateException(error);}
            }).withUserConfiguration(CompactionService.class,PersonalMemoryConfiguration.class,MemoryBackground.class);

    @Test void enabledByDefaultWithoutRequiringDatabaseInMemoryMode(){
        runner.withPropertyValues("agent.storage.mode=memory").run(context->{
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("agent.compaction.enabled",Boolean.class)).isTrue();
            assertThat(context).doesNotHaveBean(CompactionService.class).doesNotHaveBean(MemoryBackground.class)
                    .doesNotHaveBean(PersonalMemoryStore.class);
        });
    }
    @Test void explicitDisableRemainsAvailableForPersistentMode(){
        runner.withPropertyValues("agent.storage.mode=mysql","agent.compaction.enabled=false").run(context->{
            assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(CompactionService.class);
        });
    }
}
