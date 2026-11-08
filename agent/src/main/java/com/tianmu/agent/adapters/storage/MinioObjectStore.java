package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.store.ObjectStore;
import io.minio.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/** Private objects in the existing MinIO instance; public URLs never enter Agent responses. */
public final class MinioObjectStore implements ObjectStore {
    private static final int MAX_BYTES = 1048576;
    private final MinioClient client;
    private final String bucket;
    private final String prefix;
    private final java.util.function.Function<com.tianmu.agent.runtime.control.CallControl,MinioClient> controlledClients;

    public MinioObjectStore(MinioClient client, String bucket, String prefix) {
        this(client,bucket,prefix,null);
    }
    private MinioObjectStore(MinioClient client,String bucket,String prefix,java.util.function.Function<com.tianmu.agent.runtime.control.CallControl,MinioClient> controlledClients){
        if (client == null || bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..") || prefix == null || prefix.length() > 128
                || !prefix.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*/"))
            throw new IllegalArgumentException("Configure an Agent bucket and a bounded object prefix");
        this.client = client; this.bucket = bucket; this.prefix = prefix;
        this.controlledClients=controlledClients;
    }

    public static MinioObjectStore connect(String endpoint, String accessKey, String secretKey, String bucket, String prefix) {
        try {
            URI uri = URI.create(endpoint);
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank())
                throw new IllegalArgumentException();
            var http = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
            MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey)
                    .httpClient(http).build();
            MinioObjectStore store = new MinioObjectStore(client, bucket, prefix,control->{
                var bounded=http.newBuilder().eventListenerFactory(call->new okhttp3.EventListener(){
                    private com.tianmu.agent.runtime.control.CancellationToken.Registration registration;
                    @Override public void callStart(okhttp3.Call call){
                        long remaining=java.time.Duration.between(java.time.Instant.now(),control.deadline()).toMillis();
                        call.timeout().timeout(Math.max(1,Math.min(5000,remaining)),TimeUnit.MILLISECONDS);
                        registration=control.cancellation().onCancel(call::cancel);
                    }
                    @Override public void callEnd(okhttp3.Call call){if(registration!=null)registration.close();}
                    @Override public void callFailed(okhttp3.Call call,java.io.IOException failure){if(registration!=null)registration.close();}
                }).build();
                return MinioClient.builder().endpoint(endpoint).credentials(accessKey,secretKey).httpClient(bounded).build();
            });
            store.initialize();
            return store;
        } catch (AgentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Configure Agent MinIO using the existing TIANMU_MINIO_* settings or AGENT_MINIO_* overrides"); }
    }

    /** A new bucket has MinIO's default private policy; existing bucket policies are not changed. */
    public void initialize() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (Exception e) { throw unavailable(); }
    }

    private String object(String key) { ObjectKeys.validate(key); return prefix + key; }
    private static AgentException unavailable() { return new AgentException("OBJECT_STORE_UNAVAILABLE", "Private object storage is unavailable"); }

    @Override public void put(String key, byte[] body) {
        String object = object(key);
        if (body.length > MAX_BYTES) throw new AgentException("ARTIFACT_TOO_LARGE", "Artifact exceeds 1 MiB");
        long started=System.nanoTime();
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(object)
                    .stream(new ByteArrayInputStream(body), body.length, -1)
                    .contentType(key.endsWith(".html") ? "text/html; charset=utf-8" : "application/json")
                    .extraHeaders(Map.of("If-None-Match", "*")).build());
        } catch (Exception e) { throw failure(e); }
        finally{operation("PUT",started);}
    }

    @Override public void put(String key,byte[] body,com.tianmu.agent.runtime.control.CallControl control){
        if(controlledClients!=null){new MinioObjectStore(controlledClients.apply(control),bucket,prefix).controlledPut(key,body,control);return;}
        controlledPut(key,body,control);
    }
    private void controlledPut(String key,byte[] body,com.tianmu.agent.runtime.control.CallControl control){
        checkpoint(control);AgentException original;
        try{put(key,body);checkpoint(control);return;}catch(StorageFailure failure){
            if(!failure.retryable && failure.status!=412)throw failure;original=failure;
        }
        // The key and expected digest stay identical. Never retry an unknown write blindly.
        checkpoint(control);
        if(java.time.Duration.between(java.time.Instant.now(),control.deadline()).toMillis()<5000)throw original;
        try{get(key,body.length,FileObjectStore.hash(body));checkpoint(control);
            com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("storage.write_verified",true));return;
        }catch(StorageFailure read){
            checkpoint(control);if(read.status!=404 || !(original instanceof StorageFailure failed) || !failed.retryable)throw original;
        }
        // One retry after a definitive missing response; reserve time for the bounded SDK call.
        if(java.time.Duration.between(java.time.Instant.now(),control.deadline()).toMillis()<5000)throw original;
        checkpoint(control);
        com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("storage.retry_count",1));
        try{put(key,body);checkpoint(control);}
        catch(StorageFailure second){
            checkpoint(control);if(!second.retryable && second.status!=412)throw second;
            if(java.time.Duration.between(java.time.Instant.now(),control.deadline()).toMillis()<5000)throw second;
            try{get(key,body.length,FileObjectStore.hash(body));checkpoint(control);
                com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("storage.write_verified",true));
            }catch(StorageFailure unverified){checkpoint(control);throw second;}
        }
    }
    private static void checkpoint(com.tianmu.agent.runtime.control.CallControl control){
        control.beforeDispatch();
        if(!java.time.Instant.now().isBefore(control.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Object write deadline exceeded");
    }

    @Override public byte[] get(String key, long size, String sha) {
        String object = object(key);
        if (size < 0 || size > MAX_BYTES || sha == null || !sha.matches("[a-f0-9]{64}"))
            throw readFailure("INVALID_REFERENCE",0,false);
        long started=System.nanoTime();
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(object).build())) {
            byte[] body = stream.readNBytes((int) size + 1);
            if (body.length != size || !FileObjectStore.hash(body).equals(sha)) throw readFailure("INTEGRITY",0,false);
            return body;
        } catch (AgentException e) { throw e; }
        catch (Exception e) { throw failure(e); }
        finally{operation("GET",started);}
    }

    private static AgentException failure(Exception error){
        if(error instanceof io.minio.errors.ErrorResponseException e){
            int status=e.response()==null?0:e.response().code();String code=e.errorResponse()==null?"":e.errorResponse().code();
            boolean retry=status==408 || status==429 || status>=500 || java.util.Set.of("RequestTimeout","SlowDown","InternalError","ServiceUnavailable").contains(code);
            return readFailure(retry?"SERVICE":status==404?"MISSING":status==401 || status==403?"ACCESS_DENIED":status==412?"CONFLICT":"PROTOCOL",status,retry);
        }
        return readFailure(error instanceof java.io.IOException?"TRANSPORT":"PROTOCOL",0,error instanceof java.io.IOException);
    }
    private static void operation(String name,long started){
        com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("storage.operation",name,"storage.elapsed_ms",Math.max(0,(System.nanoTime()-started)/1_000_000)));
    }
    private static final class StorageFailure extends AgentException {
        final int status;final boolean retryable;
        StorageFailure(int status,boolean retryable){super(retryable?"OBJECT_STORE_UNAVAILABLE":"ARTIFACT_UNAVAILABLE",retryable?"Private object storage temporarily unavailable":"Private object operation rejected or inconsistent");this.status=status;this.retryable=retryable;}
    }

    private static AgentException readFailure(String kind,int status,boolean retryable) {
        // Only bounded diagnostics; never export SDK messages, endpoints, keys or credentials.
        com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),
                Map.of("storage.failure_kind",kind,"storage.http_status",status));
        return new StorageFailure(status,retryable);
    }

    @Override public void delete(String key) {
        String object = object(key);
        long started=System.nanoTime();
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(object).build()); }
        catch (Exception e) { throw failure(e); }
        finally{operation("DELETE",started);}
    }

    @Override public void verifyReady() {
        String key = "probe_" + UUID.randomUUID() + ".json";
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try { put(key, body); get(key, body.length, FileObjectStore.hash(body)); }
        finally { delete(key); }
    }
}
