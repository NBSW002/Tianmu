package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.Blob;
import com.tianmu.agent.runtime.store.ObjectStore;
import java.security.*;
import java.util.*;

/** All payload reads are bounded, integrity-checked and addressed by host-generated keys. */
public final class FeedbackObjects {
    private final com.tianmu.agent.feedback.FeedbackRuntimeProperties governance;
    private final ObjectStore store;
    private final ObjectMapper json;
    private final FeedbackObjectIndex index;
    public FeedbackObjects(ObjectStore store, ObjectMapper json) {this(store,json,FeedbackObjectIndex.NONE);}
    public FeedbackObjects(ObjectStore store, ObjectMapper json,FeedbackObjectIndex index) { this(store, json, index, com.tianmu.agent.feedback.FeedbackRuntimeProperties.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public FeedbackObjects(ObjectStore store, ObjectMapper json,FeedbackObjectIndex index, com.tianmu.agent.feedback.FeedbackRuntimeProperties governance) { this.governance=governance; this.store = store; this.json = json.copy().findAndRegisterModules();this.index=index; }
    public Blob put(UUID job, Object value) {
        try {
            byte[] body = json.writeValueAsBytes(value);
            String hash = sha(body), key = "fb_" + UUID.randomUUID().toString().replace("-", "") + ".json";
            var ref = new Blob(key, body.length, hash);index.register(job,ref);
            com.tianmu.agent.harness.trace.IoTimings.measure(com.tianmu.agent.harness.trace.IoTimings.Kind.FEEDBACK_OBJECT,()->{store.put(key,body);return null;});return ref;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new AgentException("INVALID_ANALYSIS", "Cannot encode analysis object"); }
    }
    public <T> T get(Blob ref, Class<T> type) {
        try {
            byte[] body = com.tianmu.agent.harness.trace.IoTimings.measure(com.tianmu.agent.harness.trace.IoTimings.Kind.FEEDBACK_OBJECT,()->store.get(ref.key(), ref.bytes(), ref.sha256()));
            if (body.length != ref.bytes() || !sha(body).equals(ref.sha256())) throw new AgentException("STORAGE_CORRUPT", "Analysis object integrity mismatch");
            return json.readValue(body, type);
        } catch (java.io.IOException e) { throw new AgentException("STORAGE_CORRUPT", "Invalid analysis object"); }
    }
    public int bytes(Object value) {
        try { return json.writeValueAsBytes(value).length; } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    public static String sha(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @org.springframework.scheduling.annotation.Scheduled(scheduler="maintenanceScheduler", fixedDelayString="${agent.feedback.runtime.object-cleanup-millis:5000}")
    public void collectExpired(){
        try{for(var expired:index.expired(governance.objectCleanupBatch())){store.delete(expired.key());index.removed(expired.key());}}
        catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("feedback_gc_unavailable type={}",unavailable.getClass().getSimpleName());}
    }
}
