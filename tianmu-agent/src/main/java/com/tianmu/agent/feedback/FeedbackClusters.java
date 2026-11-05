package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;

/** Candidate ordering only. Every original remains in the output, including isolated/short/emoji texts. */
public final class FeedbackClusters {
    public record Result(List<Unit> ordered,int exactExpressions,int clusters,String method,Map<String,String> membership) {public Result{ordered=List.copyOf(ordered);membership=Map.copyOf(membership);}}
    private record Exact(String text,String reply,boolean author,Long playback) {}
    private final FeedbackVectorIndex vectors;private final FeedbackEmbedding embedding;
    public FeedbackClusters(FeedbackVectorIndex vectors,FeedbackEmbedding embedding){
        if((vectors==null)!=(embedding==null))throw new IllegalArgumentException("Embedding and vector index must be configured together");
        if(vectors!=null && vectors.dimension()>0 && embedding.dimension()>0 && vectors.dimension()!=embedding.dimension())throw new IllegalArgumentException("Embedding dimensions differ from Milvus collection configuration");
        this.vectors=vectors;this.embedding=embedding;
    }
    public Result organize(UUID job,AccessScope owner,Request request,Source source,List<Unit> rows,CallControl control,Runnable reserve){
        var groups=new LinkedHashMap<Exact,List<Unit>>();
        for(var row:rows){check(control);if(row.source()!=source)throw new AgentException("INVALID_EVIDENCE","Mixed source input");
            groups.computeIfAbsent(new Exact(row.text(),row.replyContext(),row.author(),row.playbackMs()),k->new ArrayList<>()).add(row);}
        var membership=new HashMap<String,String>();
        if(vectors==null){int n=0;for(var members:groups.values()){String cluster="K"+(++n);members.forEach(u->membership.put(u.id(),cluster));}
            return new Result(groups.values().stream().flatMap(List::stream).toList(),groups.size(),groups.size(),"EXACT_ONLY",membership);}
        var ns=new FeedbackVectorIndex.Namespace(owner.tenantId(),owner.userId(),request.query().videoId(),source,embedding.version());
        var representatives=groups.values().stream().map(g->g.get(0)).toList();
        var refs=representatives.stream().map(FeedbackVectorIndex.Reference::from).toList();
        var positions=new HashMap<FeedbackVectorIndex.Reference,Integer>();for(int i=0;i<refs.size();i++)positions.put(refs.get(i),i);
        var encoded=new ArrayList<List<Float>>();
        for(int from=0;from<representatives.size();){
            check(control);int end=from,bytes=0;
            var framed=new ArrayList<String>();
            while(end<representatives.size() && end-from<20){
                var u=representatives.get(end);String text="text["+u.text().length()+"]:"+u.text()+"\nreply["+u.replyContext().length()+"]:"+u.replyContext();
                // JSON escaping can expand controls up to six bytes; bound before provider dispatch.
                int size=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(text).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if(size>60000)throw new AgentException("EMBEDDING_INPUT_TOO_LARGE","One expression exceeds the bounded embedding input");
                if(bytes+size>60000)break;framed.add(text);bytes+=size;end++;
            }
            var cached=vectors.fetch(ns,refs.subList(from,end),control);var missing=new ArrayList<Integer>();var texts=new ArrayList<String>();
            for(int i=from;i<end;i++)if(!cached.containsKey(refs.get(i))){missing.add(i);texts.add(framed.get(i-from));}
            var batch=new HashMap<>(cached);
            if(!missing.isEmpty()){
                reserve.run();var result=embedding.embed(texts,control);
                if(result.size()!=texts.size())throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Embedding coverage differs from inputs");
                var entries=new ArrayList<FeedbackVectorIndex.Entry>();
                for(int n=0;n<missing.size();n++){int i=missing.get(n);entries.add(new FeedbackVectorIndex.Entry(refs.get(i),result.get(n)));batch.put(refs.get(i),result.get(n));}
                vectors.upsert(ns,entries,control);
            }
            for(int i=from;i<end;i++)encoded.add(batch.get(refs.get(i)));from=end;
        }
        var members=new ArrayList<>(groups.values());var used=new HashSet<Integer>();var ordered=new ArrayList<Unit>();int clusters=0;
        for(int seed=0;seed<representatives.size();seed++){
            check(control);if(!used.add(seed))continue;clusters++;String cluster="K"+clusters;ordered.addAll(members.get(seed));members.get(seed).forEach(u->membership.put(u.id(),cluster));
            for(var candidate:vectors.search(ns,encoded.get(seed),32,control)){
                Integer next=positions.get(candidate.reference());
                // Exact snapshot version intersection: old/deleted/out-of-window ANN hits cannot contribute.
                if(next==null || used.contains(next) || candidate.similarity()<0.90)continue;
                var a=representatives.get(seed);var b=representatives.get(next);
                if(a.author()!=b.author())continue;
                if(source==Source.DANMAKU && (a.playbackMs()==null || b.playbackMs()==null || Math.abs(a.playbackMs()-b.playbackMs())>15000))continue;
                if(source==Source.COMMENT && !a.replyContext().equals(b.replyContext()))continue;
                // Star-shaped candidates, not transitive connected components or inferred duplicate opinions.
                used.add(next);ordered.addAll(members.get(next));members.get(next).forEach(u->membership.put(u.id(),cluster));
            }
        }
        if(ordered.size()!=rows.size())throw new AgentException("INVALID_EVIDENCE","Clustering lost source members");
        return new Result(ordered,groups.size(),clusters,"MILVUS_CANDIDATE_ORDERING_V1",membership);
    }
    private static void check(CallControl c){c.checkpoint();if(!java.time.Instant.now().isBefore(c.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Clustering deadline exceeded");}
}
