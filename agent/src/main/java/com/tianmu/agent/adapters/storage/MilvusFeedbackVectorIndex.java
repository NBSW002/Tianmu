package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.feedback.FeedbackVectorIndex;
import com.tianmu.agent.runtime.control.CallControl;
import okhttp3.*;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Milvus REST v2, immutable version keys, COSINE, no automatic network retries. */
public final class MilvusFeedbackVectorIndex implements FeedbackVectorIndex {
    private static final int MAX_BODY=4*1024*1024, MAX_BATCH=128;
    private final String endpoint,token,database,collection;
    private final int dimension;
    private final Duration timeout;
    private final ObjectMapper json;
    private final OkHttpClient http;
    private final boolean provenanceEnabled;

    public MilvusFeedbackVectorIndex(String endpoint,String token,String database,String collection,
                                    int dimension,Duration timeout,ObjectMapper json) {
        this(endpoint,token,database,collection,dimension,timeout,json,false);
    }
    public MilvusFeedbackVectorIndex(String endpoint,String token,String database,String collection,
                                    int dimension,Duration timeout,ObjectMapper json,boolean provenanceEnabled) {
        URI uri;
        try{uri=URI.create(endpoint);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid Milvus endpoint");}
        if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"")) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Milvus endpoint must be a bare HTTP origin");
        if(database==null || !database.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                || collection==null || !collection.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                || dimension<1 || dimension>32768 || timeout==null || timeout.compareTo(Duration.ofMillis(1))<0
                || timeout.compareTo(Duration.ofSeconds(60))>0)
            throw new IllegalArgumentException("Invalid Milvus configuration");
        if(token==null || token.length()>8192 || token.chars().anyMatch(c->c<32 || c>126))
            throw new IllegalArgumentException("Invalid Milvus token");
        this.endpoint=endpoint.replaceAll("/+$","");this.token=token;this.database=database;
        this.collection=collection;this.dimension=dimension;this.timeout=timeout;this.json=json.copy().findAndRegisterModules();
        this.provenanceEnabled=provenanceEnabled;
        http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(Math.min(2000,timeout.toMillis()),TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(),TimeUnit.MILLISECONDS).build();
    }

    @Override public void upsert(Namespace ns,List<Entry> entries,CallControl control){
        check(control);batch(entries.size());
        var data=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();
        for(var entry:entries){
            entry.reference().requireSource(ns.source());validate(entry.vector());
            String id=entry.reference().key(ns);
            if(!ids.add(id))throw new IllegalArgumentException("Duplicate vector version in batch");
            var row=new LinkedHashMap<String,Object>(Map.of("id",id,"scope_key",ns.key(),"record_id",entry.reference().recordId(),
                    "content_version",entry.reference().contentVersion(),"vector",entry.vector()));
            if(provenanceEnabled){
                var p=entry.provenance();
                if(p.origin()!=Origin.UNKNOWN && !ns.embeddingVersion().equals(p.embeddingVersion()))throw new IllegalArgumentException("Vector provenance version mismatch");
                try{row.put("provenance",json.writeValueAsString(p));}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw protocol();}
            }
            data.add(row);
        }
        var body=base();body.put("data",data);
        var result=post("upsert",body,control);
        if(!result.path("upsertCount").isIntegralNumber() || result.path("upsertCount").longValue()!=entries.size())
            throw protocol();
    }
    @Override public int dimension(){return dimension;}

    @Override public List<Candidate> search(Namespace ns,List<Float> vector,int limit,CallControl control){
        check(control);validate(vector);
        if(limit<1 || limit>256)throw new IllegalArgumentException("Candidate limit must be 1..256");
        var body=base();body.put("data",List.of(vector));body.put("annsField","vector");body.put("limit",limit);
        body.put("filter",scopeFilter(ns));body.put("consistencyLevel","Strong");
        body.put("searchParams",Map.of("metricType","COSINE","params",Map.of()));
        body.put("outputFields",List.of("id","scope_key","record_id","content_version"));
        var data=post("search",body,control);
        if(!data.isArray() || data.size()>limit)throw protocol();
        var hits=new ArrayList<Candidate>();var ids=new HashSet<String>();
        try {
            for(var row:data){
                if(!row.path("scope_key").isTextual() || !ns.key().equals(row.path("scope_key").textValue())
                        || !row.path("record_id").isTextual() || !row.path("content_version").isTextual()
                        || !row.path("id").isTextual() || !row.path("distance").isNumber())throw protocol();
                var ref=new Reference(row.path("record_id").textValue(),row.path("content_version").textValue());
                ref.requireSource(ns.source());
                if(!ref.key(ns).equals(row.path("id").textValue()) || !ids.add(ref.key(ns)))throw protocol();
                hits.add(new Candidate(ref,row.path("distance").doubleValue()));
            }
        }catch(IllegalArgumentException invalid){throw protocol();}
        hits.sort(Comparator.comparingDouble(Candidate::similarity).reversed().thenComparing(c->c.reference().recordId()));
        return List.copyOf(hits);
    }
    @Override public Map<Reference,List<Float>> fetch(Namespace ns,List<Reference> refs,CallControl control){
        var vectors=new HashMap<Reference,List<Float>>();fetchEntries(ns,refs,control).forEach((ref,entry)->vectors.put(ref,entry.vector()));return Map.copyOf(vectors);
    }
    @Override public Map<Reference,Entry> fetchEntries(Namespace ns,List<Reference> refs,CallControl control){
        check(control);batch(refs.size());var expected=new HashMap<String,Reference>();
        for(var ref:refs){ref.requireSource(ns.source());if(expected.put(ref.key(ns),ref)!=null)throw new IllegalArgumentException("Duplicate requested version");}
        var fields=new ArrayList<>(List.of("id","scope_key","record_id","content_version","vector"));if(provenanceEnabled)fields.add("provenance");
        var body=base();body.put("consistencyLevel","Strong");body.put("id",expected.keySet().stream().sorted().toList());body.put("outputFields",fields);
        var data=post("get",body,control);if(!data.isArray() || data.size()>refs.size())throw protocol();
        var found=new HashMap<Reference,Entry>();
        for(var row:data){
            var ref=expected.get(row.path("id").asText());
            if(ref==null || !ns.key().equals(row.path("scope_key").asText()) || !ref.recordId().equals(row.path("record_id").asText()) || !ref.contentVersion().equals(row.path("content_version").asText()) || !row.path("vector").isArray())throw protocol();
            var vector=new ArrayList<Float>();for(var n:row.path("vector")){if(!n.isNumber())throw protocol();vector.add(n.floatValue());}
            try{validate(vector);}catch(IllegalArgumentException invalid){throw protocol();}
            Provenance provenance=Provenance.unknown();
            if(provenanceEnabled && row.hasNonNull("provenance")){
                try{provenance=json.readValue(row.get("provenance").asText(),Provenance.class);
                    if(provenance==null || provenance.origin()!=Origin.UNKNOWN && !ns.embeddingVersion().equals(provenance.embeddingVersion()))throw protocol();
                }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw protocol();}
            }
            if(found.put(ref,new Entry(ref,vector,provenance))!=null)throw protocol();
        }
        return Map.copyOf(found);
    }

    @Override public void delete(Namespace ns,List<Reference> refs,CallControl control){
        check(control);batch(refs.size());var ids=new TreeSet<String>();
        for(var ref:refs){ref.requireSource(ns.source());ids.add("\""+ref.key(ns)+"\"");}
        var body=base();body.put("filter",scopeFilter(ns)+" and id in ["+String.join(",",ids)+"]");
        post("delete",body,control);
    }

    private Map<String,Object> base(){var b=new LinkedHashMap<String,Object>();b.put("dbName",database);b.put("collectionName",collection);return b;}
    private String scopeFilter(Namespace ns){return "scope_key == \""+ns.key()+"\"";}
    private void batch(int n){if(n<1 || n>MAX_BATCH)throw new IllegalArgumentException("Vector batch must be 1..128");}
    private void validate(List<Float> vector){
        if(vector.size()!=dimension)throw new IllegalArgumentException("Vector dimension differs from collection");
        double norm=0;
        for(var v:vector){if(v==null || !Float.isFinite(v))throw new IllegalArgumentException("Nonfinite vector");norm+=(double)v*v;}
        if(norm==0)throw new IllegalArgumentException("Zero cosine vector");
    }
    private static void check(CallControl control){
        control.checkpoint();if(!Instant.now().isBefore(control.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Vector operation deadline exceeded");
    }
    private JsonNode post(String action,Map<String,Object> body,CallControl control){
        check(control);
        try {
            byte[] bytes=json.writeValueAsBytes(body);
            if(bytes.length>MAX_BODY)throw new AgentException("VECTOR_REQUEST_TOO_LARGE","Vector request exceeds byte quota");
            var builder=new Request.Builder().url(endpoint+"/v2/vectordb/entities/"+action)
                    .post(RequestBody.create(MediaType.get("application/json"),bytes));
            if(!token.isEmpty())builder.header("Authorization","Bearer "+token);
            var call=http.newCall(builder.build());
            long millis=Math.max(1,Math.min(timeout.toMillis(),Duration.between(Instant.now(),control.deadline()).toMillis()));
            call.timeout().timeout(millis,TimeUnit.MILLISECONDS);
            try(var registration=control.cancellation().onCancel(call::cancel);var response=call.execute()){
                check(control);
                if(response.code()==429)throw new AgentException("VECTOR_RATE_LIMITED","Vector service rate limited");
                if(response.code()==401 || response.code()==403)throw new AgentException("VECTOR_AUTH_FAILED","Vector service authentication failed");
                if(!response.isSuccessful())throw unavailable();
                if(response.body()==null)throw protocol();
                byte[] result=response.body().byteStream().readNBytes(MAX_BODY+1);
                if(result.length>MAX_BODY)throw new AgentException("VECTOR_RESPONSE_TOO_LARGE","Vector response exceeds byte quota");
                check(control);var root=json.readTree(result);
                if(root==null || !root.path("code").isIntegralNumber())throw protocol();
                if(root.path("code").longValue()!=0)throw new AgentException("VECTOR_OPERATION_FAILED","Milvus rejected the operation");
                if(!root.hasNonNull("data"))throw protocol();
                return root.get("data");
            }
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){check(control);throw protocol();}
        catch(IOException failure){check(control);throw unavailable();}
    }
    private static AgentException unavailable(){return new AgentException("VECTOR_STORE_UNAVAILABLE","Vector service unavailable");}
    private static AgentException protocol(){return new AgentException("VECTOR_PROTOCOL_ERROR","Invalid vector service response");}
}
