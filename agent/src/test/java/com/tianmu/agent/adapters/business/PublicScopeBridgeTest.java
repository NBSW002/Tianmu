package com.tianmu.agent.adapters.business;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.store.ResourceScopeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicScopeBridgeTest {
    @Test void adapterSeparatesPublicReadFromCreatorAndRechecksRevokedVisibility() throws Exception {
        var json=new ObjectMapper();var publicVisible=new AtomicBoolean(true);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/agent",exchange->{
            String path=exchange.getRequestURI().getPath();Object response;
            if(path.endsWith("/principal")) response=Map.of("tenantId","site","userId","1");
            else if(path.endsWith("/authorize-readable") || path.endsWith("/authorize")) {
                var request=json.readTree(exchange.getRequestBody());Set<Long> granted=new HashSet<>();
                for(var value:request.path("videoIds")) {
                    long id=value.asLong();
                    if(id==10 || id==20 && publicVisible.get() && path.endsWith("/authorize-readable"))granted.add(id);
                }
                response=Map.of("videoIds",granted);
            } else response=Map.of();
            byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        try {
            var resources=mock(ResourceScopeStore.class);
            @SuppressWarnings("unchecked") ObjectProvider<ResourceScopeStore> provider=mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(resources);
            var adapter=new RemoteBusinessAdapter(new BusinessProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),3000),json,provider);
            var viewer=adapter.forCreate("test","概括这个视频",null,new RequestContext("20"),null);
            assertEquals(Set.of(),viewer.videoIds());assertEquals(Set.of(20L),viewer.readableVideoIds());
            assertThrows(AgentException.class,()->adapter.query(new MetricQuery(20,java.time.LocalDate.now().minusDays(1),"bullet_count"),viewer));
            var own=adapter.forCreate("test","视频10",null);
            assertEquals(Set.of(10L),own.videoIds());
            var session=UUID.randomUUID();when(resources.resourcesForSession(eq(session),any(),eq(Long.MAX_VALUE))).thenReturn(Set.of(10L,20L));
            var continued=adapter.forCreate("test","继续刚才的讨论",null,null,session);
            assertEquals(Set.of(10L),continued.videoIds());assertEquals(Set.of(10L,20L),continued.readableVideoIds());
            var run=UUID.randomUUID();when(resources.resourcesForRun(eq(run),any())).thenReturn(Set.of(20L));
            publicVisible.set(false);
            assertThrows(AgentException.class,()->adapter.forRun("test",run));
            assertThrows(AgentException.class,()->adapter.forCreate("test","视频20",null));
            var after=adapter.forCreate("test","聊点别的",null,null,session);
            assertEquals(Set.of(10L),after.readableVideoIds());
        } finally { server.stop(0); }
    }
}
