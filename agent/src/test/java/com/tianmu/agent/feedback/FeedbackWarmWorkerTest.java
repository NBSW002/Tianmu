package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedbackWarmWorkerTest {
    @Test void successfulLeaseWarmsAndCompletesButFailureRemainsRetryable(@TempDir Path dir)throws Exception{
        var json=new ObjectMapper();var compression=mock(FeedbackCompression.class);var completions=new ArrayList<Boolean>();
        var file=dir.resolve("secret");Files.writeString(file,"s".repeat(40));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/agent/vector-work/",exchange->{try{
            assertEquals("s".repeat(40),exchange.getRequestHeaders().getFirst("X-Vector-Work-Token"));
            String path=exchange.getRequestURI().getPath();String body;
            if(path.endsWith("claim"))body="{\"videoId\":1001,\"generation\":1,\"token\":\"lease\",\"tenantId\":\"tianmu\",\"ownerId\":\"881\"}";
            else if(path.endsWith("snapshot"))body="{\"unit\":{\"id\":\"C:1\",\"source\":\"COMMENT\",\"originalId\":\"1\",\"text\":\"画面清晰\",\"replyContext\":\"\",\"author\":false,\"userId\":\"882\"}}\n{\"receipt\":{}}\n";
            else{completions.add(json.readTree(exchange.getRequestBody()).path("success").asBoolean());body="";}
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
        try{
            var worker=new FeedbackWarmWorker(compression,json,new FeedbackWarmProperties("http://127.0.0.1:"+server.getAddress().getPort(),file.toString(),5000,30,10));
            worker.poll();assertEquals(List.of(true),completions);verify(compression).warm(any(),eq(1001L),argThat(rows->rows.size()==1 && rows.get(0).id().equals("C:1")),any(),any());
            doThrow(new IllegalStateException()).when(compression).warm(any(),anyLong(),anyList(),any(),any());worker.poll();assertEquals(List.of(true,false),completions);
        }finally{server.stop(0);}
    }
}
