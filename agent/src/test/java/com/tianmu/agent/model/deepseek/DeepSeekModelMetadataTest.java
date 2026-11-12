package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekModelMetadataTest {
    HttpServer server;
    final AtomicInteger requests=new AtomicInteger();
    String body="{\"data\":[{\"id\":\"deepseek-flash\",\"name\":\"DeepSeek-V4.1-Flash\",\"context_window\":1048576,\"max_output_tokens\":393216}]}";
    String authorization;
    int status=200;
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/models",exchange->{
            requests.incrementAndGet();authorization=exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            try {exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
            finally {exchange.close();}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    DeepSeekModelMetadata service(){
        var config=new DeepSeekProperties(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture-secret","deepseek-flash","deepseek-flash",Duration.ofMillis(300),384,1536);
        return new DeepSeekModelMetadata(config,new ObjectMapper());
    }
    @Test void preservesProviderValuesAndCachesDiscovery(){
        var service=service();var models=service.list();
        assertEquals(1,models.size());assertEquals("deepseek-flash",models.get(0).model());
        assertEquals(1048576L,models.get(0).contextWindow());assertEquals(393216L,models.get(0).maxOutputTokens());
        assertEquals("DeepSeek-V4.1-Flash",models.get(0).name());
        assertEquals("compatible",models.get(0).provider());assertTrue(models.get(0).fetchedAtEpochMs()>0);
        assertEquals("Bearer fixture-secret",authorization);assertEquals(models,service.list());assertEquals(1,requests.get());
    }
    @Test void absentOrInvalidLimitsNeverAcquireDefaults(){
        body="{\"data\":[{\"id\":\"a\",\"context_window\":-1},{\"id\":\"b\",\"context_window\":\"1048576\"},{\"id\":\"c\"}]}";
        assertTrue(service().list().stream().allMatch(model->model.contextWindow()==null && model.maxOutputTokens()==null && model.name()==null));
    }
    @Test void failuresAreNegativelyCachedAndDoNotExposeProviderBody(){
        status=500;body="fixture-private-error";var service=service();
        assertTrue(service.list().isEmpty());assertTrue(service.list().isEmpty());assertEquals(1,requests.get());
    }
    @Test void oversizedDiscoveryIsRejected(){body=" ".repeat(131073);assertTrue(service().list().isEmpty());}
    @Test void stalledResponseBodyIsCancelledWithinRequestDeadline(){
        var release=new java.util.concurrent.CountDownLatch(1);
        server.removeContext("/models");
        server.createContext("/models",exchange->{
            try {
                exchange.sendResponseHeaders(200,100);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
                release.await(6,java.util.concurrent.TimeUnit.SECONDS);
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        try {assertTimeoutPreemptively(Duration.ofSeconds(5),()->assertTrue(service().list().isEmpty()));}
        finally {release.countDown();}
    }
}
