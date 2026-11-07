package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import javax.sql.DataSource;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${agent.memory.enabled:false} or ${agent.compaction.enabled:false}")
public class PersonalMemoryConfiguration {
    @Bean @DependsOn("agentMigrations")
    PersonalMemoryStore personalMemoryStore(DataSource source,ObjectMapper json,MemoryRuntimeProperties governance) {
        return new MysqlPersonalMemoryStore(source,json,java.time.Clock.systemUTC(),governance.personalMaxItems(),governance);
    }
    @Bean MemoryLedger memoryLedger(DataSource source,PersonalMemoryStore store,ObjectMapper json,MemoryRuntimeProperties governance){return new MemoryLedger(source,store,json,governance);}
}
