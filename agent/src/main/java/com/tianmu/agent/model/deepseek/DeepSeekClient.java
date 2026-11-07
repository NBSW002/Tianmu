package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceAttributes;
import com.tianmu.agent.harness.trace.TraceDetails;
import com.tianmu.agent.runtime.control.CallControl;
import com.fasterxml.jackson.databind.*;
import io.opentelemetry.api.trace.Span;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.io.*;

/** Single shared credential, independently selected model IDs; no prompt/body/header logging or implicit retries. */
public final class DeepSeekClient {
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final com.tianmu.agent.model.ModelContextBuilder contexts;
    private final com.tianmu.agent.model.HarnessContextBuilder harnessContexts;
    private final io.opentelemetry.api.trace.Tracer tracer;
    private volatile long quotaBlockedUntil;
    private static final long QUOTA_COOLDOWN_NANOS=Duration.ofMinutes(5).toNanos();
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper) {
        this(config, mapper, com.tianmu.agent.model.ContextPolicy.defaults());
    }
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper, com.tianmu.agent.model.ContextPolicy contextPolicy) {
        this(config,mapper,contextPolicy,null);
    }
    public DeepSeekClient(DeepSeekProperties config,ObjectMapper mapper,com.tianmu.agent.model.ContextPolicy contextPolicy,io.opentelemetry.api.trace.Tracer tracer){
        this.tracer=tracer;
        contexts = new com.tianmu.agent.model.ModelContextBuilder(mapper, contextPolicy);
        harnessContexts = new com.tianmu.agent.model.HarnessContextBuilder(mapper,contextPolicy);
        this.config = config; this.mapper = mapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public List<Map<String, String>> messages(String instruction, Map<String, ?> input) { return contexts.build(instruction, input); }
    private com.tianmu.agent.model.ModelContextBuilder contexts(CallControl control) {
        return control.frozenContext()==null?contexts:new com.tianmu.agent.model.ModelContextBuilder(mapper,com.tianmu.agent.runtime.control.RunPolicies.context(control.frozenContext()));
    }
    public List<Map<String,String>> messages(String instruction,Map<String,?> input,CallControl control){return contexts(control).build(instruction,input);}
    public List<Map<String,Object>> harnessMessages(String instruction,com.tianmu.agent.core.AgentTypes.ModelContext context) { return harnessContexts.build(instruction,context); }
    public List<Map<String,Object>> harnessMessages(String instruction,com.tianmu.agent.core.AgentTypes.ModelContext context,CallControl control) {
        return control.frozenContext()==null?harnessContexts.build(instruction,context):new com.tianmu.agent.model.HarnessContextBuilder(mapper,com.tianmu.agent.runtime.control.RunPolicies.context(control.frozenContext())).build(instruction,context);
    }
    public JsonNode complete(String model, List<? extends Map<String, ?>> messages, int maxTokens, CallControl control) {
        return complete(model,messages,maxTokens,control,false);
    }
    public JsonNode complete(String model, List<? extends Map<String, ?>> messages, int maxTokens, CallControl control, boolean external) {
        return complete(model,messages,maxTokens,control,external,null);
    }
    public JsonNode completeHarness(String model,List<? extends Map<String,?>> messages,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> tools,int maxTokens,CallControl control) {
        return completeHarness(model,messages,tools,maxTokens,control,false);
    }
    public JsonNode completeHarness(String model,List<? extends Map<String,?>> messages,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> tools,int maxTokens,CallControl control,boolean requireTool) {
        return completeHarness(model,messages,tools,maxTokens,control,requireTool?"required":null);
    }
    public JsonNode completeHarness(String model,List<? extends Map<String,?>> messages,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> tools,int maxTokens,CallControl control,String requiredTool) {
        if(requiredTool!=null && (tools.isEmpty() || !"required".equals(requiredTool) && tools.stream().noneMatch(t->t.name().equals(requiredTool))))throw new AgentException("INVALID_DECISION","Required resource tool is not exposed");
        return complete(model,messages,maxTokens,control,false,List.copyOf(tools),requiredTool);
    }
    public JsonNode toolArguments(String text) {
        try {
            JsonNode value=mapper.readTree(text);
            if(value==null || !value.isObject())throw new IllegalArgumentException();
            return value;
        } catch(Exception e) { throw new AgentException("INVALID_DECISION","Invalid native tool arguments"); }
    }
    private JsonNode complete(String model,List<? extends Map<String,?>> messages,int maxTokens,CallControl control,boolean external,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> nativeTools) {
        return complete(model,messages,maxTokens,control,external,nativeTools,null);
    }
    private JsonNode complete(String model,List<? extends Map<String,?>> messages,int maxTokens,CallControl control,boolean external,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> nativeTools,String requiredTool) {
        if(tracer==null)return invoke(model,messages,maxTokens,control,external,nativeTools,requiredTool);
        var span=tracer.spanBuilder("model.request").startSpan();
        com.tianmu.agent.harness.trace.TraceNode.begin(span,"model.request");
        TraceAttributes.apply(span,Map.of("model.dispatched",false,"model.name",model));
        TraceAttributes.apply(span,com.tianmu.agent.harness.trace.TraceModelCall.identity());
        String run=io.opentelemetry.context.Context.current().get(com.tianmu.agent.harness.trace.TraceUsage.RUN_ID);
        if(run!=null)TraceAttributes.apply(span,Map.of("run.id",run));
        try(var scope=span.makeCurrent()){
            var result=invoke(model,messages,maxTokens,control,external,nativeTools,requiredTool);
            com.tianmu.agent.harness.trace.TraceNode.success(span);return result;
        }catch(RuntimeException error){com.tianmu.agent.harness.trace.TraceNode.failure(span,error);span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);throw error;}
        finally{span.end();}
    }
    private JsonNode invoke(String model,List<? extends Map<String,?>> messages,int maxTokens,CallControl control,boolean external,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> nativeTools,String requiredTool) {
        control.checkpoint();
        if(quotaBlockedUntil!=0 && System.nanoTime()-quotaBlockedUntil<0)
            throw new AgentException("MODEL_QUOTA_EXHAUSTED","Model provider quota is unavailable; calls paused for five minutes");
        contexts(control).check(messages);
        boolean streaming=nativeTools!=null && control.textOutput()!=null;
        TraceAttributes.apply(Span.current(),Map.of("model.tool_protocol",nativeTools==null?"json_structured":"native_tools"));
        TraceAttributes.apply(Span.current(), Map.of("model.name", model, "model.provider", config.baseUrl().getHost().endsWith("deepseek.com")?"deepseek":"compatible", "model.max_tokens", maxTokens,
                "model.temperature", 0, "model.streaming", streaming, "model.first_token_state", streaming?"PENDING":"NOT_APPLICABLE", "model.message_count", messages.size(), "model.reasoning_state", "DISABLED"));
        long nanos = Duration.between(Instant.now(), control.deadline()).toNanos();
        if (nanos <= 0) throw unavailable();
        try {
            Map<String,Object> requestBody=new LinkedHashMap<>();
            requestBody.put("model",model);requestBody.put("messages",messages);if(config.baseUrl().getHost().endsWith("deepseek.com") || model.startsWith("deepseek"))requestBody.put("thinking",Map.of("type","disabled"));
            requestBody.put("temperature",0);requestBody.put("stream",streaming);
            if(streaming)requestBody.put("stream_options",Map.of("include_usage",true));requestBody.put("max_tokens",maxTokens);
            if(nativeTools==null)requestBody.put("response_format",Map.of("type","json_object"));
            if(nativeTools!=null && !nativeTools.isEmpty()) {
                requestBody.put("tools",nativeTools.stream().map(t->Map.of("type","function","function",Map.of("name",t.name(),"description",t.description(),"parameters",t.inputSchema()))).toList());
                Object choice=requiredTool==null?"auto":"required".equals(requiredTool)?"required":Map.of("type","function","function",Map.of("name",requiredTool));
                boolean parallelSources=requiredTool==null
                        && (nativeTools.stream().anyMatch(t->java.util.Set.of("query_video_metrics","query_danmaku","read_result","analyze_video_content").contains(t.name()))
                        || nativeTools.stream().anyMatch(t->t.name().equals("analyze_comments")) && nativeTools.stream().anyMatch(t->t.name().equals("analyze_danmaku")));
                requestBody.put("tool_choice",choice);requestBody.put("parallel_tool_calls",parallelSources);
                TraceAttributes.apply(Span.current(),Map.of("model.tool_choice",requiredTool==null?"auto":requiredTool));
            }
            TraceAttributes.apply(Span.current(),com.tianmu.agent.model.ModelPayloadMetrics.measure(mapper,requestBody));
            contexts(control).check(List.of(Map.of("request",requestBody)));
            TraceDetails.capture("input", requestBody, "MODEL_REQUEST");
            byte[] payload = mapper.writeValueAsBytes(requestBody);
            String base = config.baseUrl().toString().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofNanos(nanos)).header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            long reservation=(long)payload.length+maxTokens+1024;
            JsonNode body = com.tianmu.agent.runtime.control.ModelBudget.execute(control, model, external, reservation, () -> {
                return streaming ? exchangeStream(request,control) : exchange(request, control);
            });
            TraceDetails.capture("provider_output", body, "MODEL_PROVIDER_RESPONSE");
            JsonNode choice = body.path("choices").path(0);
            if (choice.path("finish_reason").isTextual()) TraceAttributes.apply(Span.current(), Map.of("model.finish_reason", choice.path("finish_reason").asText()));
            if(nativeTools!=null && choice.path("message").path("tool_calls").isArray() && !choice.path("message").path("tool_calls").isEmpty()) {
                if(nativeTools.isEmpty() || !"tool_calls".equals(choice.path("finish_reason").asText()))throw new AgentException("INVALID_DECISION","Unexpected native tool call");
                if(requiredTool!=null && !"required".equals(requiredTool))for(var call:choice.path("message").path("tool_calls"))
                    if(!requiredTool.equals(call.path("function").path("name").asText()))throw new AgentException("INVALID_DECISION","Provider selected a tool outside the required resource step");
                return choice.path("message");
            }
            if(requiredTool!=null)throw new AgentException("INVALID_DECISION","Provider omitted the required resource tool call");
            if (!"stop".equals(choice.path("finish_reason").asText())) throw unavailable();
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual() || content.textValue().isBlank()) throw unavailable();
            if(nativeTools!=null)return choice.path("message");
            JsonNode result = responseObject(content.textValue());
            if (result == null || !result.isObject()) throw unavailable();
            return result;
        } catch (AgentException e) { throw e; }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            control.checkpoint();throw new AgentException("MODEL_ERROR","Model returned invalid structured JSON");
        }
        catch (Exception e) { control.checkpoint(); throw unavailable(); }
    }
    private JsonNode responseObject(String content) throws com.fasterxml.jackson.core.JsonProcessingException {
        try { return mapper.readTree(content); }
        catch (com.fasterxml.jackson.core.JsonProcessingException original) {
            // Only redundant closing root braces can be removed. All values and duplicate-key checks remain intact.
            String candidate=content.stripTrailing();
            for(int i=0;i<2 && candidate.endsWith("}");i++) {
                candidate=candidate.substring(0,candidate.length()-1).stripTrailing();
                try {
                    JsonNode parsed=mapper.readTree(candidate);
                    if(parsed!=null && parsed.isObject()) {
                        TraceAttributes.apply(Span.current(),Map.of("model.response_repaired",true));
                        return parsed;
                    }
                } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) {}
            }
            TraceAttributes.apply(Span.current(),Map.of("model.validation_error",original.getClass().getSimpleName()));
            throw original;
        }
    }
    private static final ScheduledExecutorService STREAM_DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"model-stream-deadlines");t.setDaemon(true);return t;});
    private JsonNode exchangeStream(HttpRequest request,CallControl control) {
        long started=System.nanoTime();var first=new java.util.concurrent.atomic.AtomicBoolean();
        control.beforeDispatch();
        TraceAttributes.apply(Span.current(),Map.of("model.dispatched",true));
        var future=http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
        var body=new java.util.concurrent.atomic.AtomicReference<InputStream>();
        Runnable close=()->{future.cancel(true);var stream=body.get();if(stream!=null)try{stream.close();}catch(IOException ignored){}};
        long remaining=Math.max(1,Duration.between(Instant.now(),control.deadline()).toMillis());
        var timeout=STREAM_DEADLINES.schedule(close,remaining,TimeUnit.MILLISECONDS);
        try(var registration=control.cancellation().onCancel(close)){
            var response=future.get(remaining,TimeUnit.MILLISECONDS);body.set(response.body());control.checkpoint();if(!Instant.now().isBefore(control.deadline()))throw unavailable();
            TraceAttributes.apply(Span.current(),Map.of("model.http_status",response.statusCode()));
            if(response.statusCode()==401||response.statusCode()==403)throw new AgentException("MODEL_AUTH_FAILED","Provider authentication failed");
            if(response.statusCode()==402){quotaBlockedUntil=System.nanoTime()+QUOTA_COOLDOWN_NANOS;throw new AgentException("MODEL_QUOTA_EXHAUSTED","Model quota exhausted");}
            if(response.statusCode()!=200)throw unavailable();
            TraceAttributes.apply(Span.current(),Map.of("model.streaming",true));
            if(!response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
                TraceAttributes.apply(Span.current(),Map.of("model.streaming",false,"model.first_token_state","NOT_APPLICABLE"));
                byte[] bytes=response.body().readNBytes(131073);if(bytes.length>131072)throw unavailable();return mapper.readTree(bytes);
            }
            var parser=new NativeSseResponse(mapper,text->{if(first.compareAndSet(false,true))TraceAttributes.apply(Span.current(),Map.of("model.first_token_state","RECEIVED","model.first_token_ms",(System.nanoTime()-started)/1_000_000));control.textOutput().accept(text);});var line=new ByteArrayOutputStream();var data=new StringBuilder();int total=0,next;
            while((next=response.body().read())!=-1){
                if(++total>2097152||line.size()>131072)throw unavailable();control.checkpoint();
                if(next!=10){line.write(next);continue;}
                String text=line.toString(java.nio.charset.StandardCharsets.UTF_8).replaceFirst("\\r$","");line.reset();
                if(text.isEmpty()){if(!data.isEmpty()){parser.accept(data.toString());data.setLength(0);if(parser.done())break;}}
                else if(text.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(text.substring(5).stripLeading());if(data.length()>131072)throw unavailable();}
            }
            if(line.size()>0){String tail=line.toString(java.nio.charset.StandardCharsets.UTF_8).stripTrailing();if(tail.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(tail.substring(5).stripLeading());}}
            if(!data.isEmpty())parser.accept(data.toString());return parser.result();
        }catch(AgentException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();control.checkpoint();throw unavailable();}
        finally{timeout.cancel(false);close.run();}
    }
    private JsonNode exchange(HttpRequest request, CallControl control) {
        long requestStarted = System.nanoTime();
        try {
            control.beforeDispatch();
            TraceAttributes.apply(Span.current(),Map.of("model.dispatched",true));
            CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> new com.tianmu.agent.resource.BoundedHttpBody(131072));
            try (var registration = control.cancellation().onCancel(() -> future.cancel(true))) {
                HttpResponse<byte[]> response;
                try { response = future.get(Math.max(1, Duration.between(Instant.now(), control.deadline()).toNanos()), TimeUnit.NANOSECONDS); }
                catch (TimeoutException e) { future.cancel(true); throw unavailable(); }
                catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw unavailable(); }
                catch (CancellationException e) { control.checkpoint(); throw unavailable(); }
                control.checkpoint();
                TraceAttributes.apply(Span.current(), Map.of("model.http_status", response.statusCode(), "model.response_ms", Math.max(0, (System.nanoTime() - requestStarted) / 1_000_000)));
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw new AgentException("MODEL_AUTH_FAILED", "Model provider authentication failed");
                if(response.statusCode()==402){
                    quotaBlockedUntil=System.nanoTime()+QUOTA_COOLDOWN_NANOS;
                    throw new AgentException("MODEL_QUOTA_EXHAUSTED","Model provider rejected the quota; calls paused for five minutes");
                }
                if (response.statusCode() == 429 || response.statusCode() >= 500) throw unavailable();
                if (response.statusCode() != 200) throw new AgentException("MODEL_REQUEST_REJECTED", "Model provider rejected request");
                return mapper.readTree(response.body());
            }
        } catch (AgentException e) { throw e; }
        catch (Exception e) { control.checkpoint(); throw unavailable(); }
        finally{TraceAttributes.apply(Span.current(),Map.of("model.response_ms",Math.max(0,(System.nanoTime()-requestStarted)/1_000_000)));}
    }
    private AgentException unavailable() { return new AgentException("MODEL_UNAVAILABLE","Model response unavailable"); }
}
