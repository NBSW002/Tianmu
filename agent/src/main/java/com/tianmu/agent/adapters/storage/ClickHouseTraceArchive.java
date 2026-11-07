package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceEnvelope;
import com.tianmu.agent.query.TraceArchive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Read-only HTTP queries against the existing archive. No browser-supplied SQL or endpoint. */
@Component
public final class ClickHouseTraceArchive implements TraceArchive {
    private final com.tianmu.agent.harness.trace.TraceDeliveryProperties governance;

    private final ObjectMapper json;
    private final String endpoint, user, passwordFile;
    private final Semaphore permits;

    public ClickHouseTraceArchive(ObjectMapper json,
            @Value("${harness.trace.query.endpoint:}") String endpoint,
            @Value("${harness.trace.query.user:trace_reader}") String user,
            @Value("${harness.trace.query.password-file:}") String passwordFile) { this(json, endpoint, user, passwordFile, com.tianmu.agent.harness.trace.TraceDeliveryProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public ClickHouseTraceArchive(ObjectMapper json,
            @Value("${harness.trace.query.endpoint:}") String endpoint,
            @Value("${harness.trace.query.user:trace_reader}") String user,
            @Value("${harness.trace.query.password-file:}") String passwordFile, com.tianmu.agent.harness.trace.TraceDeliveryProperties governance) { this.governance=governance;permits=new Semaphore(governance.queryParallelism());
        this.json = json; this.endpoint = endpoint; this.user = user; this.passwordFile = passwordFile;
    }

    @Override public List<TraceEnvelope> find(String traceId) {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
            throw new AgentException("INVALID_REQUEST", "Invalid trace ID");
        if (endpoint.isBlank()) throw unavailable();
        if (!permits.tryAcquire()) throw new AgentException("CAPACITY_EXCEEDED", "Trace query capacity exceeded");
        HttpURLConnection connection = null;
        try {
            URI base = URI.create(endpoint);
            if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                    || base.getRawQuery() != null || base.getUserInfo() != null || base.getFragment() != null) throw unavailable();
            String sql = "SELECT envelope_json FROM tianmu_trace.trace_spans_current WHERE trace_id={trace:String} "
                    + "ORDER BY start_time, ingested_at LIMIT "+(governance.queryMaxSpans()+1)+" FORMAT JSONEachRow";
            String query = "?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8) + "&param_trace=" + traceId
                    + "&max_execution_time="+governance.queryExecutionSeconds()+"&max_result_bytes="+governance.queryMaxBytes()+"&result_overflow_mode=throw&wait_end_of_query=1";
            connection = (HttpURLConnection) URI.create(endpoint + query).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(governance.queryConnectMillis()); connection.setReadTimeout(governance.queryReadMillis());
            connection.setRequestMethod("GET"); connection.setUseCaches(false);
            connection.setRequestProperty("X-ClickHouse-User", user);
            if (!passwordFile.isBlank()) connection.setRequestProperty("X-ClickHouse-Key", Files.readString(Path.of(passwordFile)).strip());
            if (connection.getResponseCode() != 200 || connection.getHeaderField("X-ClickHouse-Exception-Code") != null) throw unavailable();
            byte[] body;
            try (var input = connection.getInputStream()) { body = input.readNBytes(governance.queryMaxBytes() + 1); }
            if (body.length > governance.queryMaxBytes()) throw tooLarge();
            List<TraceEnvelope> spans = new ArrayList<>();
            for (String line : new String(body, StandardCharsets.UTF_8).split("\\n")) {
                if (line.isBlank()) continue;
                if (spans.size() == governance.queryMaxSpans()) throw tooLarge();
                var span = json.readValue(json.readTree(line).path("envelope_json").asText(), TraceEnvelope.class);
                if (!traceId.equals(span.traceId()) || span.attributes() == null) throw unavailable();
                spans.add(span);
            }
            return List.copyOf(spans);
        } catch (AgentException error) { throw error; }
        catch (Exception error) { throw unavailable(); }
        finally { if (connection != null) connection.disconnect(); permits.release(); }
    }
    private static AgentException unavailable() { return new AgentException("TRACE_UNAVAILABLE", "Trace archive unavailable"); }
    private static AgentException tooLarge() { return new AgentException("TRACE_TOO_LARGE", "Trace exceeds viewer limits"); }
}
