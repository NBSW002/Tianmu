package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.core.FeedbackAnalysis.Unit;
import com.tianmu.agent.runtime.control.CallControl;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Host-only candidate index. A hit is NOT evidence or authorization to read a record.
 * Callers must reauthorize and intersect hits with the frozen snapshot's exact versions.
 */
public interface FeedbackVectorIndex {
    default int dimension(){return 0;}
    record Namespace(String tenantId,String userId,long videoId,Source source,String embeddingVersion) {
        public Namespace {
            for(String s:List.of(tenantId,userId,embeddingVersion))
                if(s.isBlank() || s.length()>256) throw new IllegalArgumentException("Invalid vector namespace");
            if(videoId<=0 || source==null) throw new IllegalArgumentException("Invalid vector source");
        }
        public String key(){return hash(tenantId,userId,Long.toString(videoId),source.name(),embeddingVersion);}
    }
    record Reference(String recordId,String contentVersion) {
        public Reference {
            if(recordId==null || !recordId.matches("[CD]:[1-9][0-9]{0,18}")
                    || contentVersion==null || !contentVersion.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid vector reference");
        }
        public static Reference from(Unit u){
            return new Reference(u.id(),hash(u.source().name(),u.text(),u.replyContext(),Boolean.toString(u.author()),
                    Objects.toString(u.playbackMs(),null),u.userId(),Objects.toString(u.createdAt(),null)));
        }
        public String key(Namespace ns){return hash(ns.key(),recordId,contentVersion);}
        public void requireSource(Source source){
            if(!recordId.startsWith(source==Source.COMMENT?"C:":"D:"))
                throw new IllegalArgumentException("Reference source differs from namespace");
        }
    }
    record Entry(Reference reference,List<Float> vector) {
        public Entry {Objects.requireNonNull(reference);vector=List.copyOf(vector);}
    }
    record Candidate(Reference reference,double similarity) {
        public Candidate {
            Objects.requireNonNull(reference);
            if(!Double.isFinite(similarity) || similarity < -1.0001 || similarity > 1.0001)
                throw new IllegalArgumentException("Invalid cosine score");
        }
    }
    void upsert(Namespace namespace,List<Entry> entries,CallControl control);
    default Map<Reference,List<Float>> fetch(Namespace namespace,List<Reference> references,CallControl control){return Map.of();}
    List<Candidate> search(Namespace namespace,List<Float> vector,int limit,CallControl control);
    void delete(Namespace namespace,List<Reference> references,CallControl control);

    /** Length framing distinguishes null, empty strings and values containing delimiters. */
    private static String hash(String... values){
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(var value:values){
                byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes==null?-1:bytes.length).array());
                if(bytes!=null)digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
