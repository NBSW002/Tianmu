package com.tianmu.agent.adapters.business;

import com.tianmu.agent.core.VideoContent;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.DanmakuTarget;
import com.tianmu.agent.core.DanmakuTypes;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.query.BusinessQueryPort;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.store.ResourceScopeStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods=false)
@Profile("business")
@EnableConfigurationProperties(BusinessProperties.class)
public class BusinessAdapters {
    @Bean com.tianmu.agent.faq.ClasspathFaqPort businessFaq(
            @org.springframework.beans.factory.annotation.Value("${agent.faq.location:classpath:faq/business.json}") org.springframework.core.io.Resource resource) {
        return new com.tianmu.agent.faq.ClasspathFaqPort(resource, null);
    }
    @Bean RemoteBusinessAdapter remoteBusinessAdapter(BusinessProperties properties, ObjectMapper json, ObjectProvider<ResourceScopeStore> repositories) {
        return new RemoteBusinessAdapter(properties, json, repositories);
    }
}

final class RemoteBusinessAdapter implements IdentityPort, BusinessQueryPort, com.tianmu.agent.query.DanmakuQueryPort, com.tianmu.agent.video.VideoContentPort, com.tianmu.agent.interaction.CreatorPublicationPort, com.tianmu.agent.query.VideoPagePort, com.tianmu.agent.feedback.FeedbackSnapshotPort {
    private record RawSample(String id, int bucket, String text, boolean truncated, Long playbackMs) {}
    private record AnalysisResponse(DanmakuTypes.Query query, long totalMessages, long uniqueSenders,
            List<DanmakuTypes.Bucket> buckets, List<RawSample> samples, Instant dataAsOf, String source, boolean demo, com.tianmu.agent.core.AudienceData.Overview audience,com.tianmu.agent.core.AudienceReportData.Sources sources) {}
    private final BusinessProperties config;
    private final ObjectMapper json;
    private final ObjectProvider<ResourceScopeStore> repositories;
    @Override public void authorize(AccessScope scope,long videoId) {
        if(!scope.videoIds().contains(videoId))throw new AgentException("FORBIDDEN","Feedback video outside scope");
        allowed(scope,Set.of(videoId),true);
    }
    @Override public com.tianmu.agent.feedback.FeedbackSnapshotPort.Receipt export(com.tianmu.agent.core.FeedbackAnalysis.Request input,AccessScope owner,CallControl control,
            java.util.function.Consumer<java.util.List<com.tianmu.agent.core.FeedbackAnalysis.Unit>> consumer) {
        authorize(owner,input.query().videoId());control.checkpoint();
        long millis=Math.min(90000,Duration.between(Instant.now(),control.deadline()).toMillis());
        if(millis<1)throw new AgentException("BUSINESS_TIMEOUT","Snapshot deadline exceeded");
        java.util.concurrent.atomic.AtomicReference<java.io.InputStream> streamRef=new java.util.concurrent.atomic.AtomicReference<>();
        var finished=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            var payload=Map.of("videoId",input.query().videoId(),"start",input.query().start().toString(),"end",input.query().end().toString(),"sources",input.sources());
            var request=HttpRequest.newBuilder(URI.create(config.baseUrl().toString().replaceAll("/+$","")+"/internal/agent/feedback-snapshot"))
                    .timeout(Duration.ofMillis(millis)).header("Authorization","Bearer "+credentials.token(owner.authorizationRef()))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
            var future=http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
            Runnable close=()->{future.cancel(true);var body=streamRef.get();if(body!=null)try{body.close();}catch(java.io.IOException ignored){}};
            java.util.concurrent.CompletableFuture.runAsync(()->{if(!finished.get())close.run();},java.util.concurrent.CompletableFuture.delayedExecutor(millis,TimeUnit.MILLISECONDS));
            try(var registration=control.cancellation().onCancel(close)) {
                var response=future.get(millis,TimeUnit.MILLISECONDS);streamRef.set(response.body());
                try(var body=new java.io.BufferedInputStream(response.body())) {
                    if(response.statusCode()==401)throw new AgentException("UNAUTHORIZED","Business login expired");
                    if(response.statusCode()==403)throw new AgentException("FORBIDDEN","Feedback authorization revoked");
                      if(response.statusCode()==422){
                          var error=json.readTree(body.readNBytes(4096));String code=error.path("code").asText();
                          if(!java.util.Set.of("FULL_ANALYSIS_TOO_LARGE","FEEDBACK_UNIT_TOO_LARGE","COMMENT_CONTEXT_TOO_DEEP","INVALID_COMMENT_CONTEXT").contains(code))code="INVALID_EVIDENCE";
                          throw new AgentException(code,"Feedback export rejected by its bounded snapshot contract");
                      }
                    if(response.statusCode()!=200 || !response.headers().firstValue("Content-Type").orElse("").startsWith("application/x-ndjson"))
                        throw new AgentException("BUSINESS_UNAVAILABLE","Feedback snapshot export failed");
                    com.tianmu.agent.feedback.FeedbackSnapshotPort.Receipt receipt=null;long bytes=0;int rows=0;
                    var batch=new java.util.ArrayList<com.tianmu.agent.core.FeedbackAnalysis.Unit>();
                    while(true) {
                        control.checkpoint();var line=new java.io.ByteArrayOutputStream();int next;
                        while((next=body.read())!=-1 && next!='\n') {
                            line.write(next);if(line.size()>262144 || ++bytes>67108864)throw new AgentException("FULL_ANALYSIS_TOO_LARGE","Snapshot stream exceeds byte limits");
                        }
                        if(line.size()==0 && next==-1)break;
                        if(receipt!=null)throw new AgentException("INVALID_EVIDENCE","Data after snapshot receipt");
                        var record=json.readTree(line.toByteArray());if(!record.isObject() || record.size()!=1)throw new AgentException("INVALID_EVIDENCE","Invalid snapshot frame");
                        if(record.has("unit")) {
                            if(++rows>20000)throw new AgentException("FULL_ANALYSIS_TOO_LARGE","Snapshot record limit exceeded");
                            batch.add(json.treeToValue(record.get("unit"),com.tianmu.agent.core.FeedbackAnalysis.Unit.class));
                            if(batch.size()==100){consumer.accept(java.util.List.copyOf(batch));batch.clear();}
                        } else if(record.has("receipt"))receipt=json.treeToValue(record.get("receipt"),com.tianmu.agent.feedback.FeedbackSnapshotPort.Receipt.class);
                        else throw new AgentException("INVALID_EVIDENCE","Unknown snapshot frame");
                        if(next==-1)break;
                    }
                    if(receipt==null || receipt.dataAsOf().isAfter(Instant.now().plusSeconds(5)))throw new AgentException("INVALID_EVIDENCE","Missing valid snapshot receipt");
                    if(!batch.isEmpty())consumer.accept(java.util.List.copyOf(batch));return receipt;
                }
            }
        } catch(AgentException e){throw e;}
        catch(Exception e){control.checkpoint();throw new AgentException("BUSINESS_UNAVAILABLE","Feedback snapshot transport unavailable");}
        finally {finished.set(true);var stream=streamRef.get();if(stream!=null)try{stream.close();}catch(java.io.IOException ignored){}}
    }
    private final HttpClient http;
    private final BusinessCredentials credentials = new BusinessCredentials();
    @Override public void publish(com.tianmu.agent.interaction.CreatorDraft draft, AccessScope scope) {
        if (!scope.videoIds().contains(Long.parseLong(draft.videoId())) || !scope.userId().equals(draft.userId())
                || !scope.tenantId().equals(draft.tenantId())) throw new AgentException("FORBIDDEN", "Draft owner mismatch");
        JsonNode response = request("POST", "/internal/agent/creator-publications", credentials.token(scope.authorizationRef()),
                Map.of("videoId",draft.videoId(),"kind",draft.kind(),"items",draft.items()),null);
        if (!response.path("published").asBoolean(false)) throw new AgentException("PUBLICATION_UNAVAILABLE", "发送结果尚未确认，请重试确认状态。");
    }
    RemoteBusinessAdapter(BusinessProperties config, ObjectMapper json, ObjectProvider<ResourceScopeStore> repositories) {
        this.config = config; this.json = json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.repositories = repositories;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMillis())).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private JsonNode request(String method, String path, String token, Object body, CallControl control) {
        return request(method, path, token, body, control, 65536, config.timeoutMillis());
    }
    private JsonNode request(String method, String path, String token, Object body, CallControl control, int maxBytes, int timeoutMillis) {
        try {
            if (control != null) control.checkpoint();
            long millis = timeoutMillis;
            if (control != null) millis = Math.min(millis, Duration.between(Instant.now(), control.deadline()).toMillis());
            if (millis < 1) throw new AgentException("BUSINESS_TIMEOUT", "Business call deadline exceeded");
            String base = config.baseUrl().toString().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(millis))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, ignored -> new com.tianmu.agent.resource.BoundedHttpBody(maxBytes));
            try (var registration = control == null ? null : control.cancellation().onCancel(() -> future.cancel(true))) {
                HttpResponse<byte[]> response = future.get(millis, TimeUnit.MILLISECONDS);
                if (control != null) control.checkpoint();
                if (response.statusCode() == 401) throw new AgentException("UNAUTHORIZED", "Business login is no longer valid");
                if (response.statusCode() == 403) throw new AgentException("FORBIDDEN", "Video is outside your current scope");
                if (path.equals("/internal/agent/creator-publications") && Set.of(400,409,422).contains(response.statusCode())) {
                    String code = json.readTree(response.body()).path("code").asText();
                    if (Set.of("INVALID_DRAFT","DRAFT_CONFLICT").contains(code)) throw new AgentException(code,"请检查草稿内容与时间。");
                }
                if (response.statusCode() == 422) {
                    String code = json.readTree(response.body()).path("code").asText();
                    if (Set.of("FULL_ANALYSIS_TOO_LARGE", "COMMENT_CONTEXT_TOO_DEEP", "INVALID_COMMENT_CONTEXT").contains(code))
                        throw new AgentException(code, "评论数据或回复上下文超出完整分析范围，请缩小时间范围后重试。");
                }
                if (response.statusCode() != 200 || response.body().length > maxBytes)
                    throw new AgentException("BUSINESS_UNAVAILABLE", "Business service response unavailable");
                return json.readTree(response.body());
            } catch (TimeoutException e) { future.cancel(true); throw new AgentException("BUSINESS_TIMEOUT", "Business service timed out"); }
            catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw new AgentException("CANCELLED", "Business call interrupted"); }
        } catch (AgentException e) { throw e; }
        catch (Exception e) { if (control != null) control.checkpoint(); throw new AgentException("BUSINESS_UNAVAILABLE", "Business service unavailable"); }
    }
    @Override public AccessScope authenticate(String credential) {
        if (credential == null || credential.isBlank() || credential.length() > 8192)
            throw new AgentException("UNAUTHORIZED", "Business login is required");
        JsonNode principal = request("GET", "/internal/agent/principal", credential, null, null);
        String tenant = principal.path("tenantId").asText(), user = principal.path("userId").asText();
        if (!tenant.matches("[A-Za-z0-9_-]{1,64}") || !user.matches("[1-9][0-9]{0,18}"))
            throw new AgentException("BUSINESS_UNAVAILABLE", "Invalid business principal");
        return new AccessScope(tenant, user, Set.of(), credentials.register(credential));
    }
    private Set<Long> allowed(AccessScope owner, Set<Long> requested, boolean requireAll) {
        if (requested.size() > 100) throw new AgentException("INVALID_REQUEST", "Too many target videos");
        if (requested.isEmpty()) return Set.of();
        JsonNode answer = request("POST", "/internal/agent/authorize", credentials.token(owner.authorizationRef()), Map.of("videoIds", requested), null);
        if (!answer.path("videoIds").isArray()) throw new AgentException("BUSINESS_UNAVAILABLE", "Invalid authorization response");
        Set<Long> result = new HashSet<>();
        for (var value : answer.path("videoIds")) {
            long id;
            try {
                if (value.isIntegralNumber() && value.canConvertToLong()) id = value.longValue();
                else if (value.isTextual() && value.textValue().matches("[1-9][0-9]{0,18}")) id = Long.parseLong(value.textValue());
                else throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new AgentException("BUSINESS_UNAVAILABLE", "Invalid authorization response"); }
            if (id <= 0 || !requested.contains(id)) throw new AgentException("BUSINESS_UNAVAILABLE", "Invalid authorization response");
            result.add(id);
        }
        if (requireAll && !result.containsAll(requested)) throw new AgentException("FORBIDDEN", "Video is outside your current scope");
        return Set.copyOf(result);
    }
    private AccessScope grant(AccessScope owner, Set<Long> resources, boolean requireAll) {
        return new AccessScope(owner.tenantId(), owner.userId(), allowed(owner, resources, requireAll), owner.authorizationRef());
    }
    @Override public AccessScope forCreate(String credential, String message, UUID reply) {
        AccessScope principal = authenticate(credential); Set<Long> resources = new HashSet<>();
        if (reply != null) resources.addAll(repositories.getObject().resourcesForRun(reply, principal));
        var matcher = DanmakuTarget.matcher(message);
        while (matcher.find()) try { resources.add(Long.parseLong(matcher.group(1))); }
        catch (NumberFormatException e) { throw new AgentException("INVALID_REQUEST", "Invalid video ID"); }
        return grant(principal, resources, true);
    }
    @Override public AccessScope forCreate(String credential,String message,UUID reply,com.tianmu.agent.core.RequestContext page,UUID session) {
        AccessScope current = forCreate(credential,message,reply);
        Set<Long> required = new HashSet<>(current.videoIds());
        if (page!=null) required.add(Long.parseLong(page.videoId()));
        if(page==null && required.isEmpty() && session!=null && com.tianmu.agent.application.ConversationFocus.continuation(message)
                && com.tianmu.agent.preparation.AnalysisSourceSelection.mentions(message))
            required.addAll(allowed(current,repositories.getObject().resourcesForSession(session,current,Long.MAX_VALUE),false));
        Set<Long> granted = new HashSet<>(allowed(current,required,true));
        return new AccessScope(current.tenantId(),current.userId(),granted,current.authorizationRef());
    }
    @Override public com.tianmu.agent.query.VideoPagePort.Page page(long videoId,AccessScope scope) {
        if (!scope.videoIds().contains(videoId)) throw new AgentException("FORBIDDEN","Current page outside scope");
        JsonNode data = request("GET","/internal/agent/video-context?videoId="+videoId,credentials.token(scope.authorizationRef()),null,null);
        try { return json.treeToValue(data,com.tianmu.agent.query.VideoPagePort.Page.class); }
        catch (Exception e) { throw new AgentException("BUSINESS_UNAVAILABLE","Invalid page metadata"); }
    }
    @Override public AccessScope forRun(String credential, UUID id) {
        AccessScope principal = authenticate(credential);
        return grant(principal, repositories.getObject().resourcesForRun(id, principal), true);
    }
    @Override public void renew(AccessScope saved,AccessScope authenticated){
        IdentityPort.super.renew(saved,authenticated);allowed(authenticated,saved.videoIds(),true);credentials.renew(saved.authorizationRef(),authenticated.authorizationRef());
    }
    @Override public AccessScope forSession(String credential, UUID id, long before) {
        AccessScope principal = authenticate(credential);
        return grant(principal, repositories.getObject().resourcesForSession(id, principal, before), false);
    }
    @Override public MetricData query(MetricQuery query, AccessScope scope) { return query(query, scope, null); }
    @Override public MetricData query(MetricQuery query, AccessScope scope, CallControl control) {
        if (!scope.videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "Video is outside prepared scope");
        if (!"bullet_count".equals(query.metric())) throw new AgentException("METRIC_UNSUPPORTED", "Unsupported metric");
        JsonNode answer = request("GET", "/internal/agent/metrics?videoId=" + query.videoId() + "&date=" + query.date() + "&metric=bullet_count",
                credentials.token(scope.authorizationRef()), null, control);
        try { return json.treeToValue(answer, MetricData.class); }
        catch (Exception e) { throw new AgentException("INVALID_EVIDENCE", "Business metric schema mismatch"); }
    }
    @Override public com.tianmu.agent.core.DanmakuTypes.Dataset query(com.tianmu.agent.core.DanmakuTypes.Query query, AccessScope scope, CallControl control) {
        if (!scope.videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "videoId is outside prepared scope");
        query.checkTime(Instant.now());
        String start = java.net.URLEncoder.encode(query.start().toString(), java.nio.charset.StandardCharsets.UTF_8);
        String end = java.net.URLEncoder.encode(query.end().toString(), java.nio.charset.StandardCharsets.UTF_8);
        JsonNode answer = request("GET", "/internal/agent/danmaku-analysis?videoId=" + query.videoId() + "&start=" + start + "&end=" + end,
                credentials.token(scope.authorizationRef()), null, control, 4_194_304, 10_000);
        try {
            // The bridge returns raw snippets. Grouped occurrences belong to the Agent's tool/evidence model.
            AnalysisResponse raw = json.treeToValue(answer, AnalysisResponse.class);
            if(raw.sources()!=null)throw new IllegalArgumentException("Expected standalone danmaku evidence");
            return new DanmakuTypes.Dataset(raw.query(), raw.totalMessages(), raw.uniqueSenders(), raw.buckets(),
                    raw.samples().stream().map(s -> new DanmakuTypes.Sample(s.id(), s.bucket(), s.text(), s.truncated(), List.of(new DanmakuTypes.SampleOccurrence(s.id(),s.bucket(),s.truncated(),s.playbackMs())))).toList(),
                    raw.dataAsOf(), raw.source(), raw.demo(), raw.audience());
        }
        catch (Exception e) { throw new AgentException("INVALID_EVIDENCE", "Analysis schema mismatch"); }
    }
    @Override public com.tianmu.agent.core.VideoContent.Source source(long videoId, AccessScope scope, CallControl control) {
        if (!scope.videoIds().contains(videoId)) throw new AgentException("FORBIDDEN", "Video outside prepared scope");
        JsonNode result = request("GET", "/internal/agent/video-source?videoId=" + videoId,
                credentials.token(scope.authorizationRef()), null, control);
        try { return json.treeToValue(result, com.tianmu.agent.core.VideoContent.Source.class); }
        catch (Exception e) { throw new AgentException("INVALID_EVIDENCE", "Video metadata schema mismatch"); }
    }
    @Override public com.tianmu.agent.core.VideoContent.Media media(long videoId, String version, long startMs, long endMs, AccessScope scope, CallControl control) {
        if (!scope.videoIds().contains(videoId) || !version.matches("[a-f0-9]{64}")) throw new AgentException("FORBIDDEN", "Video outside scope");
        JsonNode result = request("GET", "/internal/agent/video-content-media?videoId=" + videoId + "&version=" + version + "&startMs=" + startMs + "&endMs=" + endMs,
                credentials.token(scope.authorizationRef()), null, control, 8_000_000, 60_000);
        try { return json.treeToValue(result, com.tianmu.agent.core.VideoContent.Media.class); }
        catch (Exception e) { throw new AgentException("INVALID_EVIDENCE", "Video media schema mismatch"); }
    }

}
