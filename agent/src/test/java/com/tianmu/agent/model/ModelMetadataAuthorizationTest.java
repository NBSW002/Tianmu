package com.tianmu.agent.model;

import com.tianmu.agent.api.ModelsController;
import com.tianmu.agent.core.AgentException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelMetadataAuthorizationTest {
    @Test void discoveryRequiresAuthenticatedIdentityBeforeProviderAccess(){
        var accesses=new AtomicInteger();var beans=new StaticListableBeanFactory();
        beans.addBean("metadata",(ModelMetadataPort)()->{accesses.incrementAndGet();return List.of(new ModelMetadataPort.Metadata("deepseek","flash",1048576L,null,1));});
        var controller=new ModelsController(credential->{if(!"valid".equals(credential))throw new AgentException("UNAUTHORIZED","Invalid credential");return null;},beans.getBeanProvider(RuntimeModelCatalog.class),beans.getBeanProvider(ModelMetadataPort.class));
        assertThrows(AgentException.class,()->controller.metadata(null));
        assertThrows(AgentException.class,()->controller.metadata("Bearer invalid"));assertEquals(0,accesses.get());
        assertEquals(1048576L,controller.metadata("Bearer valid").get(0).contextWindow());assertEquals(1,accesses.get());
    }
}
