package com.tianmu.agent.api;
import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.model.RuntimeModelCatalog;
import com.tianmu.agent.model.ModelMetadataPort;
import com.tianmu.agent.core.AgentException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/agent/models")
public final class ModelsController {
    private final IdentityPort identity;private final ObjectProvider<RuntimeModelCatalog> catalog;private final ObjectProvider<ModelMetadataPort> metadata;
    public ModelsController(IdentityPort identity,ObjectProvider<RuntimeModelCatalog> catalog,ObjectProvider<ModelMetadataPort> metadata){this.identity=identity;this.catalog=catalog;this.metadata=metadata;}
    @GetMapping public List<RuntimeModelCatalog.Choice> list(@RequestHeader(value="Authorization",required=false)String auth){
        if(auth==null||!auth.startsWith("Bearer "))throw new AgentException("UNAUTHORIZED","Login required");identity.authenticate(auth.substring(7));
        var models=catalog.getIfAvailable();return models==null?List.of():models.choices();
    }
    @GetMapping("/metadata") public List<ModelMetadataPort.Metadata> metadata(@RequestHeader(value="Authorization",required=false)String auth){
        if(auth==null||!auth.startsWith("Bearer "))throw new AgentException("UNAUTHORIZED","Login required");identity.authenticate(auth.substring(7));
        var provider=metadata.getIfAvailable();return provider==null?List.of():provider.list();
    }
}
