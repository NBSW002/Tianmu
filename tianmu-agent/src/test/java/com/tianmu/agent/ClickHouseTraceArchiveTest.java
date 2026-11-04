package com.tianmu.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.adapters.storage.ClickHouseTraceArchive;
import com.tianmu.agent.core.AgentException;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ClickHouseTraceArchiveTest {
    @Test void httpLookupIsBoundedReadOnlyAndRejectsInvalidOrPartialResults() throws Exception {
        var json = new ObjectMapper();
        var body = new AtomicReference<>(json.writeValueAsString(Map.of("envelope_json", json.writeValueAsString(Map.of(
                "traceId", TraceQueryTest.TRACE, "spanId", "1234567890abcdef", "name", "agent.run", "attributes", Map.of())))) + "\n");
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var archive = new ClickHouseTraceArchive(json, "http://127.0.0.1:" + server.getAddress().getPort(), "trace_reader", "");
            assertEquals(1, archive.find(TraceQueryTest.TRACE).size());
            assertTrue(request.get().startsWith("GET "));
            assertTrue(request.get().contains("param_trace=" + TraceQueryTest.TRACE));
            assertEquals("INVALID_REQUEST", assertThrows(AgentException.class, () -> archive.find("' OR 1=1")).code());
            body.set(body.get().repeat(2001));
            assertEquals("TRACE_TOO_LARGE", assertThrows(AgentException.class, () -> archive.find(TraceQueryTest.TRACE)).code());
            body.set("Code: backend failure with private details");
            var failure = assertThrows(AgentException.class, () -> archive.find(TraceQueryTest.TRACE));
            assertEquals("TRACE_UNAVAILABLE", failure.code());
            assertFalse(failure.getMessage().contains("private details"));
        } finally { server.stop(0); }
    }
    @Test void unconfiguredArchiveFailsExplicitly() {
        var archive = new ClickHouseTraceArchive(new ObjectMapper(), "", "trace_reader", "");
        assertEquals("TRACE_UNAVAILABLE", assertThrows(AgentException.class, () -> archive.find(TraceQueryTest.TRACE)).code());
    }
}
