package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="feedback.vector-work.enabled",havingValue="true")
public class FeedbackVectorWorkConfiguration {
    @Bean FeedbackVectorWork feedbackVectorWork(JdbcTemplate db,BridgeProperties properties){return new FeedbackVectorWork(db,properties.tenantId());}
}
