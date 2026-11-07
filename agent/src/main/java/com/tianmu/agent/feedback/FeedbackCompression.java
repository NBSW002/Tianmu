package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.text.Normalizer;
import java.util.*;

/** Pure task-member clustering and representative selection. Full membership never enters model projection. */
public final class FeedbackCompression {
    private static final int MAX_STATS_KEYS=10000; // Keeps each sealed aggregation below the object-store limit.
    public static final String VERSION="feedback-representatives-v1";
    public static final String FIVE_VERSION=FeedbackAnalysis.FIVE_BUCKET_VERSION;
    public static boolean representativeVersion(String version){return VERSION.equals(version) || FIVE_VERSION.equals(version);}
    public record Excluded(String id,String reason) {}
    public record SourceStats(Map<String,Long> daily,Map<Long,Long> playback,long playbackMissing) {
        public SourceStats {daily=Map.copyOf(daily);playback=Map.copyOf(playback);}
    }
    public record SourceMaterial(Source source,List<Unit> eligible,List<Excluded> excluded,SourceStats stats) {
        public SourceMaterial(Source source,List<Unit> eligible,List<Excluded> excluded){this(source,eligible,excluded,null);}
        public SourceMaterial {eligible=List.copyOf(eligible);excluded=List.copyOf(excluded);}
    }
    public record Excerpt(String memberId,String field,String snapshotHash,String valueHash,int startCodePoint,int endCodePoint,int originalCodePoints) {}
    public record Representative(String id,Source source,String text,String context,Long playbackMs,
                                 Long likes,long exactMessages,boolean excerpt,Excerpt textProvenance,Excerpt contextProvenance) {}
    public record Cluster(String id,List<String> members,List<Representative> representatives) {
        public Cluster {members=List.copyOf(members);representatives=List.copyOf(representatives);}
    }
    public record BucketMetric(String mode,String fallback,double threshold,int expressions,String transformVersion) {}
    public record Material(String version,FeedbackCompressionProperties policy,String protocolRevision,Blob manifest,Counts rawCounts,
                           Counts eligibleCounts,List<Excluded> excluded,int exactExpressions,List<Cluster> clusters,
                           List<FeedbackModels.Input> inputs,int representativeCount,int modelBytes,List<String> unclassified,List<Representative> unclassifiedExamples,BucketMetric bucketMetric,SourceStats stats) {
        public Material {excluded=List.copyOf(excluded);clusters=List.copyOf(clusters);inputs=List.copyOf(inputs);unclassified=unclassified==null?List.of():List.copyOf(unclassified);unclassifiedExamples=unclassifiedExamples==null?List.of():List.copyOf(unclassifiedExamples);}
        public Material withoutStats(){return new Material(version,policy,protocolRevision,manifest,rawCounts,eligibleCounts,excluded,exactExpressions,clusters,inputs,representativeCount,modelBytes,unclassified,unclassifiedExamples,bucketMetric,null);}
        public Map<String,Object> summary(){var values=new LinkedHashMap<String,Object>();values.put("raw",rawCounts.total());values.put("excluded",excluded.size());values.put("eligible",eligibleCounts.total());values.put("exact",exactExpressions);values.put("clusters",clusters.size());values.put("representatives",representativeCount);values.put("model_bytes",modelBytes);values.put("method",version);values.put("policy",protocolRevision);values.put("unclassified",unclassified.size());if(bucketMetric!=null)values.put("bucket_metric",Map.of("mode",bucketMetric.mode(),"fallback",bucketMetric.fallback(),"threshold",bucketMetric.threshold(),"expressions",bucketMetric.expressions(),"transform_version",bucketMetric.transformVersion()));return Collections.unmodifiableMap(values);}
    }
    private record Exact(Source source,String text,String context,boolean author,Long bucket) {}
    private record Expression(Unit exemplar,List<Unit> members) {}
    private final FeedbackEmbedding embedding;private final FeedbackVectorIndex vectors;
    private final FeedbackCompressionProperties policy;private final ObjectMapper json;
    private FeedbackBucketProperties buckets=new FeedbackBucketProperties(5,.65,2,50,64);
    public void bucketPolicy(FeedbackBucketProperties value){buckets=Objects.requireNonNull(value);}
    public FeedbackCompression(FeedbackEmbedding embedding,FeedbackVectorIndex vectors,FeedbackCompressionProperties policy,ObjectMapper json){
        this.embedding=embedding;this.vectors=vectors;this.policy=policy;this.json=json;
        if(policy.enabled() && (embedding==null || vectors==null))throw new IllegalArgumentException("Compression requires Embedding and vector storage");
        if(embedding!=null && vectors!=null && embedding.dimension()!=vectors.dimension())throw new IllegalArgumentException("Compression vector dimension mismatch");
    }
    public FeedbackCompressionProperties policy(){return policy;}
    public String revision(){return FeedbackObjects.sha((policy.revision()+"|"+(embedding==null?"none":embedding.version()+":"+embedding.dimension())+"|representatives-text-v1|cosine-normalized-star-v1").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public String fiveRevision(){return FeedbackObjects.sha((revision()+"|"+buckets.revision()+"|five-buckets-expression-equal-supported-contextguard-task-centering-three-stats-v5").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    public SourceMaterial prepare(Source source,List<Unit> rows,CallControl control){
        var accepted=new ArrayList<Unit>();var excluded=new ArrayList<Excluded>();var daily=new TreeMap<String,Long>();var playback=new TreeMap<Long,Long>();long missing=0;boolean statsOverflow=false;
        for(var u:rows){check(control);if(u.source()!=source)throw new AgentException("INVALID_EVIDENCE","Mixed preparation partition");
            if(!statsOverflow){if(u.createdAt()!=null)daily.merge(u.createdAt().atZone(DanmakuTypes.ZONE).toLocalDate().toString(),1L,Long::sum);
                if(source==Source.DANMAKU){if(u.playbackMs()==null)missing++;else playback.merge(u.playbackMs(),1L,Long::sum);}
                if(daily.size()+playback.size()>MAX_STATS_KEYS){statsOverflow=true;daily.clear();playback.clear();}}
            String clean=Normalizer.normalize(u.text(),Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+"," ").strip();
            long effective=clean.codePoints().filter(Character::isLetterOrDigit).count();
            String reason=clean.isEmpty()?"EMPTY":effective<policy.minEffectiveChars()?"SHORT_TEXT":null;
            // Author remarks remain source records but do not become audience representatives or statistics.
            if(u.author())reason="AUTHOR_CONTEXT";
            if(reason!=null){excluded.add(new Excluded(u.id(),reason));continue;}
            accepted.add(new Unit(u.id(),u.source(),u.originalId(),clean,u.replyContext(),false,u.playbackMs(),u.userId(),u.createdAt(),u.likeCount()));
        }
        return new SourceMaterial(source,accepted,excluded,statsOverflow?null:new SourceStats(daily,playback,missing));
    }
    public Material compress(UUID job,AccessScope owner,Request request,Blob manifest,Counts raw,List<SourceMaterial> partitions,
                             CallControl control,Runnable reserveEmbedding){
        if(embedding==null || vectors==null)throw new AgentException("VECTOR_REQUIRED","Representative reports require the vector pipeline");
        var rows=new ArrayList<Unit>();var excluded=new ArrayList<Excluded>();var sourceSet=EnumSet.noneOf(Source.class);
        for(var p:partitions){if(!sourceSet.add(p.source()) || !request.sources().contains(p.source()))throw new AgentException("INVALID_EVIDENCE","Invalid material partitions");rows.addAll(p.eligible());excluded.addAll(p.excluded());}
        if(!sourceSet.equals(request.sources()) || rows.size()+excluded.size()!=raw.total())throw new AgentException("INVALID_EVIDENCE","Material counts differ from shared snapshot");
        rows.sort(Comparator.comparing(Unit::id));
        var exact=new LinkedHashMap<Exact,List<Unit>>();
        for(var u:rows){check(control);var key=new Exact(u.source(),u.text(),u.replyContext(),u.author(),u.playbackMs()==null?null:u.playbackMs()/policy.playbackBucketMs());exact.computeIfAbsent(key,k->new ArrayList<>()).add(u);}
        var expressions=new ArrayList<Expression>();
        Comparator<Unit> ranking=Comparator.<Unit,Boolean>comparing(u->u.likeCount()==null).thenComparing(u->u.likeCount()==null?0L:u.likeCount(),Comparator.reverseOrder()).thenComparing(Unit::id);
        for(var members:exact.values()){members.sort(ranking);expressions.add(new Expression(members.get(0),List.copyOf(members)));}
        if((long)expressions.size()*embedding.dimension()*Float.BYTES*3>policy.vectorBytes())throw new AgentException("MATERIAL_BUDGET_EXCEEDED","Task vector memory quota exceeded");
        var encoded=encode(owner,request,expressions,control,reserveEmbedding);
        if(FIVE_VERSION.equals(request.rulesVersion()))return fiveBuckets(manifest,raw,excluded,rows,expressions,encoded,control,mergeStats(partitions));
        var assigned=new BitSet(expressions.size());var clusters=new ArrayList<Cluster>();var inputs=new ArrayList<FeedbackModels.Input>();
        var allGroups=new ArrayList<FeedbackModels.Group>();int reps=0,bytes=0;
        for(int seed=0;seed<expressions.size();seed++){
            check(control);if(assigned.get(seed))continue;
            final int s=seed;var neighbors=new ArrayList<Integer>();
            for(int n=0;n<expressions.size();n++){if(n%64==0)check(control);if(n!=seed && !assigned.get(n) && cosine(encoded.get(seed),encoded.get(n))>=policy.minSimilarity() && compatible(expressions.get(seed).exemplar(),expressions.get(n).exemplar()))neighbors.add(n);}
            neighbors.sort(Comparator.<Integer>comparingDouble(n->cosine(encoded.get(s),encoded.get(n))).reversed().thenComparingInt(n->n));
            var memberIndices=new ArrayList<Integer>();memberIndices.add(seed);memberIndices.addAll(neighbors.subList(0,Math.min(policy.neighbors(),neighbors.size())));memberIndices.forEach(assigned::set);
            float[] center=new float[embedding.dimension()];for(int n:memberIndices)for(int d=0;d<center.length;d++)center[d]+=encoded.get(n)[d];normalize(center);
            var selected=new ArrayList<Integer>();
            for(var source:Source.values()){
                var candidates=memberIndices.stream().filter(n->expressions.get(n).exemplar().source()==source).toList();if(candidates.isEmpty())continue;
                var ordered=new ArrayList<>(candidates);ordered.sort((a,b)->compare(expressions.get(a),expressions.get(b),encoded.get(a),encoded.get(b),center));
                var first=ordered.stream().filter(n->cosine(encoded.get(n),center)>=policy.minCentrality()).findFirst().orElseThrow(()->new AgentException("REPRESENTATIVE_SELECTION_FAILED","Source lacks a representative"));selected.add(first);
            }
            if(reps+selected.size()>policy.maxRepresentatives())throw new AgentException("MATERIAL_BUDGET_EXCEEDED","Representative quota cannot cover all task clusters and sources");
            String clusterId="K"+(clusters.size()+1);var representatives=new ArrayList<Representative>();var members=new ArrayList<Unit>();memberIndices.forEach(n->members.addAll(expressions.get(n).members()));
            for(int n:selected){var e=expressions.get(n);var u=e.exemplar();representatives.add(representative(u,e.members().size(),manifest.sha256()));}
            for(var source:Source.values()){
                var sourceMembers=members.stream().filter(u->u.source()==source).toList();if(sourceMembers.isEmpty())continue;
                var sourceReps=representatives.stream().filter(u->u.source()==source).toList();String text=representativeText(sourceReps);
                var group=new FeedbackModels.Group(clusterId+"_"+source.name(),source,text,"",false,null,sourceMembers.size(),sourceMembers.stream().map(Unit::id).toList(),clusterId);allGroups.add(group);
            }
            reps+=representatives.size();bytes=projectedBytes(allGroups);
            if(bytes>policy.packageBytes())throw new AgentException("MATERIAL_BUDGET_EXCEEDED","Representative input byte quota exceeded");
            clusters.add(new Cluster(clusterId,members.stream().map(Unit::id).toList(),representatives));
        }
        // Mandatory source representatives are allocated for EVERY cluster before optional slots.
        var clusterOrder=new ArrayList<Integer>();for(int n=0;n<clusters.size();n++)clusterOrder.add(n);
        clusterOrder.sort(Comparator.<Integer>comparingInt(n->clusters.get(n).members().size()).reversed().thenComparingInt(n->n));
        for(int index:clusterOrder){
            if(reps>=policy.maxRepresentatives())break;check(control);var cluster=clusters.get(index);var chosen=new ArrayList<>(cluster.representatives());
            var ids=new HashSet<>(cluster.members());var candidates=new ArrayList<Integer>();
            for(int n=0;n<expressions.size();n++)if(ids.contains(expressions.get(n).exemplar().id()))candidates.add(n);
            float[] center=new float[embedding.dimension()];for(int n:candidates)for(int d=0;d<center.length;d++)center[d]+=encoded.get(n)[d];normalize(center);
            candidates.sort((a,b)->compare(expressions.get(a),expressions.get(b),encoded.get(a),encoded.get(b),center));
            for(int n:candidates){
                if(chosen.size()>=Math.min(policy.representativesPerCluster(),policy.maxPerCluster()) || reps>=policy.maxRepresentatives())break;
                var e=expressions.get(n);var u=e.exemplar();
                if(chosen.stream().anyMatch(r->r.id().equals(u.id())) || cosine(encoded.get(n),center)<policy.minCentrality())continue;
                boolean duplicate=false;for(var r:chosen){int pos=-1;for(int k:candidates)if(expressions.get(k).exemplar().id().equals(r.id())){pos=k;break;}if(pos>=0 && cosine(encoded.get(n),encoded.get(pos))>=policy.nearDuplicateSimilarity()){duplicate=true;break;}}if(duplicate)continue;
                var r=representative(u,e.members().size(),manifest.sha256());
                var next=new ArrayList<>(chosen);next.add(r);
                var members=rows.stream().filter(u2->ids.contains(u2.id())).toList();
                int delta=projectedBytes(groups(cluster.id(),members,next))-projectedBytes(groups(cluster.id(),members,chosen));
                if(bytes+delta>policy.packageBytes())continue;chosen.add(r);bytes+=delta;reps++;
            }
            clusters.set(index,new Cluster(cluster.id(),cluster.members(),chosen));
        }
        allGroups.clear();var byId=new HashMap<String,Unit>();rows.forEach(u->byId.put(u.id(),u));
        for(var cluster:clusters)for(var source:Source.values()){
            var members=cluster.members().stream().map(byId::get).filter(u->u.source()==source).toList();if(members.isEmpty())continue;
            allGroups.add(new FeedbackModels.Group(cluster.id()+"_"+source.name(),source,representativeText(cluster.representatives().stream().filter(r->r.source()==source).toList()),"",false,null,members.size(),members.stream().map(Unit::id).toList(),cluster.id()));
        }
        // Keep a cluster's source groups in one model batch; each batch has only representative text.
        var batch=new ArrayList<FeedbackModels.Group>();int batchBytes=0;
        for(var cluster:clusters){var groups=allGroups.stream().filter(g->cluster.id().equals(g.candidateCluster())).toList();int size=projectedBytes(groups);
            if(size>policy.batchBytes())throw new AgentException("MATERIAL_BUDGET_EXCEEDED","One cluster exceeds the model batch byte quota");
            if(batchBytes+size>policy.batchBytes() && !batch.isEmpty()){inputs.add(new FeedbackModels.Input(WorkKind.CLASSIFY,batch,List.of(),null));batch=new ArrayList<>();batchBytes=0;}
            batch.addAll(groups);batchBytes+=size;
        }
        if(!batch.isEmpty())inputs.add(new FeedbackModels.Input(WorkKind.CLASSIFY,batch,List.of(),null));
        var people=new HashSet<String>();rows.stream().filter(u->u.userId()!=null).forEach(u->people.add(u.userId()));long comments=rows.stream().filter(u->u.source()==Source.COMMENT).count();
        bytes=projectedBytes(allGroups);if(bytes>policy.packageBytes())throw new AgentException("MATERIAL_BUDGET_EXCEEDED","Representative model projection exceeds global byte quota");
        return new Material(VERSION,policy,revision(),manifest,raw,new Counts(comments,rows.size()-comments,0,0,people.size()),excluded,expressions.size(),clusters,inputs,reps,bytes,List.of(),List.of(),null,mergeStats(partitions));
    }
    private SourceStats mergeStats(List<SourceMaterial> parts){var daily=new TreeMap<String,Long>();var playback=new TreeMap<Long,Long>();long missing=0;for(var part:parts){if(part.stats()==null)return null;part.stats().daily().forEach((k,v)->daily.merge(k,v,Math::addExact));part.stats().playback().forEach((k,v)->playback.merge(k,v,Math::addExact));missing=Math.addExact(missing,part.stats().playbackMissing());if(daily.size()+playback.size()>MAX_STATS_KEYS)return null;}return new SourceStats(daily,playback,missing);}
    private Material fiveBuckets(Blob manifest,Counts raw,List<Excluded> excluded,List<Unit> rows,List<Expression> expressions,List<float[]> encoded,CallControl control,SourceStats stats){
        var eligibleIndices=new ArrayList<Integer>();var unclassified=new ArrayList<String>();
        for(int n=0;n<expressions.size();n++){if(contextDependent(expressions.get(n).exemplar()))expressions.get(n).members().forEach(u->unclassified.add(u.id()));else eligibleIndices.add(n);}
        var transformed=FeedbackVectorTransform.apply(eligibleIndices.stream().map(encoded::get).toList(),buckets,control);
        if(transformed.mode().equals("TASK_CENTERED_COSINE")){
            long totalMembers=0,nearMeanMembers=0;
            for(int i=0;i<eligibleIndices.size();i++){check(control);int count=expressions.get(eligibleIndices.get(i)).members().size();totalMembers+=count;if(transformed.vectors().get(i)==null)nearMeanMembers+=count;}
            if(totalMembers>0 && nearMeanMembers/(double)totalMembers>buckets.centerMaxExcludedFraction())transformed=new FeedbackVectorTransform.Result("RAW_COSINE","TOO_MANY_NEAR_MEAN_MEMBERS",buckets.minSimilarity(),eligibleIndices.stream().map(encoded::get).toList());
        }
        var rankingVectors=new ArrayList<>(encoded);var usable=new ArrayList<Integer>();
        for(int i=0;i<eligibleIndices.size();i++){int n=eligibleIndices.get(i);var vector=transformed.vectors().get(i);if(vector==null)expressions.get(n).members().forEach(u->unclassified.add(u.id()));else{usable.add(n);rankingVectors.set(n,vector);}}
        eligibleIndices=usable;
        var partition=FiveBucketClustering.cluster(eligibleIndices.stream().map(rankingVectors::get).toList(),buckets.maxBuckets(),buckets.maxIterations(),transformed.threshold(),buckets.minMembers(),buckets.candidateSamples(),control);
        var clusters=new ArrayList<Cluster>();var groupRows=new ArrayList<FeedbackModels.Group>();var inputs=new ArrayList<FeedbackModels.Input>();int representatives=0;
        for(int n:partition.unclassified())expressions.get(eligibleIndices.get(n)).members().forEach(u->unclassified.add(u.id()));
        for(var bucket:partition.buckets()){
            var indices=bucket.stream().map(eligibleIndices::get).toList();
            check(control);float[] center=new float[embedding.dimension()];for(int n:indices)for(int d=0;d<center.length;d++)center[d]+=rankingVectors.get(n)[d];normalize(center);
            var members=new ArrayList<Unit>();indices.forEach(n->members.addAll(expressions.get(n).members()));var selected=new ArrayList<Integer>();
            for(var source:Source.values()){
                var candidates=new ArrayList<>(indices.stream().filter(n->expressions.get(n).exemplar().source()==source).toList());
                if(candidates.isEmpty())continue;candidates.sort((a,b)->compare(expressions.get(a),expressions.get(b),rankingVectors.get(a),rankingVectors.get(b),center));selected.add(candidates.get(0));
            }
            if(representatives+selected.size()>policy.maxRepresentatives())throw new AgentException("MATERIAL_BUDGET_EXCEEDED","Representative quota cannot cover all buckets");
            var reps=new ArrayList<Representative>();for(int n:selected){var e=expressions.get(n);reps.add(representative(e.exemplar(),e.members().size(),manifest.sha256()));}
            String id="B"+(clusters.size()+1);clusters.add(new Cluster(id,members.stream().map(Unit::id).toList(),reps));groupRows.addAll(groups(id,members,reps));representatives+=reps.size();
        }
        int bytes=projectedBytes(groupRows);if(bytes>policy.packageBytes())throw new AgentException("MATERIAL_BUDGET_EXCEEDED","Representative model projection exceeds global byte quota");
        var byId=new HashMap<String,Unit>();rows.forEach(u->byId.put(u.id(),u));
        var order=new ArrayList<Integer>();for(int n=0;n<clusters.size();n++)order.add(n);order.sort(Comparator.<Integer>comparingInt(n->clusters.get(n).members().size()).reversed().thenComparingInt(n->n));
        for(int index:order){
            if(representatives>=policy.maxRepresentatives())break;check(control);var cluster=clusters.get(index);var ids=new HashSet<>(cluster.members());var selected=new ArrayList<>(cluster.representatives());var choices=new ArrayList<Integer>();
            for(int n=0;n<expressions.size();n++)if(ids.contains(expressions.get(n).exemplar().id()))choices.add(n);
            float[] center=new float[embedding.dimension()];for(int n:choices)for(int d=0;d<center.length;d++)center[d]+=rankingVectors.get(n)[d];normalize(center);choices.sort((a,b)->compare(expressions.get(a),expressions.get(b),rankingVectors.get(a),rankingVectors.get(b),center));
            var members=cluster.members().stream().map(byId::get).toList();
            for(int pass=0;pass<2;pass++)for(int n:choices){
                if(selected.size()>=Math.min(3,Math.min(policy.representativesPerCluster(),policy.maxPerCluster())) || representatives>=policy.maxRepresentatives())break;
                var e=expressions.get(n);if(selected.stream().anyMatch(r->r.id().equals(e.exemplar().id())))continue;
                boolean duplicate=false;for(var r:selected)for(int previous:choices)if(expressions.get(previous).exemplar().id().equals(r.id()) && cosine(rankingVectors.get(n),rankingVectors.get(previous))>=policy.nearDuplicateSimilarity()){duplicate=true;break;}if(duplicate && pass==0)continue;
                var next=new ArrayList<>(selected);next.add(representative(e.exemplar(),e.members().size(),manifest.sha256()));int delta=projectedBytes(groups(cluster.id(),members,next))-projectedBytes(groups(cluster.id(),members,selected));
                if(bytes+delta>policy.packageBytes() || projectedBytes(groups(cluster.id(),members,next))>policy.batchBytes())continue;selected=next;bytes+=delta;representatives++;
            }
            clusters.set(index,new Cluster(cluster.id(),cluster.members(),selected));
        }
        groupRows.clear();for(var cluster:clusters)groupRows.addAll(groups(cluster.id(),cluster.members().stream().map(byId::get).toList(),cluster.representatives()));
        bytes=projectedBytes(groupRows);if(bytes>policy.packageBytes())throw new AgentException("MATERIAL_BUDGET_EXCEEDED","Representative model projection exceeds global byte quota");
        var batch=new ArrayList<FeedbackModels.Group>();int batchBytes=0;
        for(var cluster:clusters){var groups=groupRows.stream().filter(g->cluster.id().equals(g.candidateCluster())).toList();int size=projectedBytes(groups);
            if(size>policy.batchBytes())throw new AgentException("MATERIAL_BUDGET_EXCEEDED","One bucket exceeds model batch quota");
            if(batchBytes+size>policy.batchBytes() && !batch.isEmpty()){inputs.add(new FeedbackModels.Input(WorkKind.CLASSIFY,batch,List.of(),null));batch=new ArrayList<>();batchBytes=0;}batch.addAll(groups);batchBytes+=size;}
        if(!batch.isEmpty())inputs.add(new FeedbackModels.Input(WorkKind.CLASSIFY,batch,List.of(),null));
        var people=new HashSet<String>();rows.stream().filter(u->u.userId()!=null).forEach(u->people.add(u.userId()));long comments=rows.stream().filter(u->u.source()==Source.COMMENT).count();
        var examples=new ArrayList<Representative>();var unclassifiedSet=new HashSet<>(unclassified);
        rows.stream().filter(u->unclassifiedSet.contains(u.id())).sorted(Comparator.<Unit,Boolean>comparing(u->u.likeCount()==null).thenComparing(u->u.likeCount()==null?0L:u.likeCount(),Comparator.reverseOrder()).thenComparing(Unit::id)).limit(3).forEach(u->examples.add(representative(u,1,manifest.sha256())));
        var metric=new BucketMetric(transformed.mode(),transformed.fallback(),transformed.threshold(),transformed.vectors().size(),transformed.mode().equals("TASK_CENTERED_COSINE")?"task-mean-centered-v1":"raw-cosine-v1");
        return new Material(FIVE_VERSION,policy,fiveRevision(),manifest,raw,new Counts(comments,rows.size()-comments,0,0,people.size()),excluded,expressions.size(),clusters,inputs,representatives,bytes,unclassified,examples,metric,stats);
    }
    private int projectedBytes(List<FeedbackModels.Group> groups){return write(groups.stream().map(g->FeedbackModelProjection.group(g,json)).toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    private List<FeedbackModels.Group> groups(String id,List<Unit> members,List<Representative> reps){
        var out=new ArrayList<FeedbackModels.Group>();for(var source:Source.values()){
            var part=members.stream().filter(u->u.source()==source).toList();if(part.isEmpty())continue;
            out.add(new FeedbackModels.Group(id+"_"+source.name(),source,representativeText(reps.stream().filter(r->r.source()==source).toList()),"",false,null,part.size(),part.stream().map(Unit::id).toList(),id));
        }return out;
    }
    private List<float[]> encode(AccessScope owner,Request request,List<Expression> expressions,CallControl control,Runnable reserve){
        var out=new ArrayList<float[]>(Collections.nCopies(expressions.size(),null));
        for(var source:Source.values()){
            var positions=new ArrayList<Integer>();for(int i=0;i<expressions.size();i++)if(expressions.get(i).exemplar().source()==source)positions.add(i);
            var ns=new FeedbackVectorIndex.Namespace(owner.tenantId(),owner.userId(),request.query().videoId(),source,embedding.version()+":representatives-text-v1");
            for(int offset=0;offset<positions.size();){check(control);int end=offset,requestBytes=0;
                while(end<positions.size() && end-offset<16){int size=write(expressions.get(positions.get(end)).exemplar().text()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if(size>60000)throw new AgentException("EMBEDDING_INPUT_TOO_LARGE","One expression exceeds embedding input quota");
                    if(requestBytes+size>60000)break;requestBytes+=size;end++;}
                var part=positions.subList(offset,end);offset=end;var refs=part.stream().map(i->FeedbackVectorIndex.Reference.from(expressions.get(i).exemplar())).toList();
                var cached=new HashMap<>(vectors.fetch(ns,refs,control));var missing=new ArrayList<Integer>();for(int i=0;i<refs.size();i++)if(!cached.containsKey(refs.get(i)))missing.add(i);
                if(!missing.isEmpty()){reserve.run();var computed=embedding.embed(missing.stream().map(i->expressions.get(part.get(i)).exemplar().text()).toList(),control);if(computed.size()!=missing.size())throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Embedding member count mismatch");
                    var entries=new ArrayList<FeedbackVectorIndex.Entry>();for(int i=0;i<missing.size();i++){var ref=refs.get(missing.get(i));cached.put(ref,computed.get(i));entries.add(new FeedbackVectorIndex.Entry(ref,computed.get(i)));}vectors.upsert(ns,entries,control);}
                for(int i=0;i<part.size();i++){var v=cached.get(refs.get(i));if(v==null || v.size()!=embedding.dimension())throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Embedding dimensions mismatch");float[] f=new float[v.size()];for(int n=0;n<f.length;n++){f[n]=v.get(n);if(!Float.isFinite(f[n]))throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Invalid embedding value");}normalize(f);out.set(part.get(i),f);}
            }
        }
        return out;
    }
    private String representativeText(List<Representative> rows){return write(rows.stream().map(r->{
        var m=new LinkedHashMap<String,Object>();m.put("id",r.id());m.put("text",r.text());m.put("context",r.context());m.put("source",r.source());
        m.put("playback_ms",r.playbackMs());m.put("likes",r.likes());m.put("likes_available",r.likes()!=null);m.put("exact_messages",r.exactMessages());m.put("excerpt",r.excerpt());return m;
    }).toList());}
    private Representative representative(Unit u,long frequency,String snapshotHash){
        String text=clip(u.text(),policy.textChars()),context=clip(u.replyContext(),policy.contextChars());
        return new Representative(u.id(),u.source(),text,context,u.playbackMs(),u.likeCount(),frequency,!text.equals(u.text()),
            excerpt(u.id(),"clean_text",snapshotHash,u.text(),text),context.isEmpty()?null:excerpt(u.id(),"replyContext",snapshotHash,u.replyContext(),context));
    }
    private static Excerpt excerpt(String id,String field,String snapshotHash,String original,String selected){return new Excerpt(id,field,snapshotHash,FeedbackObjects.sha(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)),0,selected.codePointCount(0,selected.length()),original.codePointCount(0,original.length()));}
    private int compare(Expression a,Expression b,float[] av,float[] bv,float[] center){
        var x=a.exemplar();var y=b.exemplar();int c=Boolean.compare(x.likeCount()==null,y.likeCount()==null);if(c!=0)return c;
        c=x.likeCount()!=null?Long.compare(y.likeCount(),x.likeCount()):0;if(c!=0)return c;
        c=Integer.compare(b.members().size(),a.members().size());if(c!=0)return c;
        c=Double.compare(cosine(bv,center),cosine(av,center));return c!=0?c:x.id().compareTo(y.id());
    }
    private boolean compatible(Unit a,Unit b){if(a.source()==Source.COMMENT && b.source()==Source.COMMENT)return a.replyContext().equals(b.replyContext());if(a.playbackMs()!=null && b.playbackMs()!=null)return Math.abs(a.playbackMs()-b.playbackMs())<=policy.playbackBucketMs();return !a.text().matches(".*(这里|这个|这段|那里|刚才).*?") && !b.text().matches(".*(这里|这个|这段|那里|刚才).*?");}
    private static double cosine(float[] a,float[] b){double dot=0;for(int i=0;i<a.length;i++)dot+=(double)a[i]*b[i];return dot;}
    private static boolean contextDependent(Unit unit){String text=unit.text();String context=unit.replyContext();return unit.source()==Source.COMMENT && !context.isBlank() && !context.equals("[]") || text.matches(".*(这里|这个|这段|那里|刚才).*") || text.matches("^(完全同意|同意|说得对|我也是|就是|没错|确实|是的|不对|不行)[\\p{P}\\p{Z}\\s]*$");}
    private static void check(CallControl c){c.checkpoint();if(!java.time.Instant.now().isBefore(c.deadline()))throw new AgentException("DEADLINE_EXCEEDED","Material preparation deadline exceeded");}
    private static void normalize(float[] a){double norm=0;for(float v:a)norm+=(double)v*v;if(!Double.isFinite(norm) || norm<=0)throw new AgentException("EMBEDDING_PROTOCOL_ERROR","Empty embedding norm");float scale=(float)(1/Math.sqrt(norm));for(int i=0;i<a.length;i++)a[i]*=scale;}
    private static String clip(String s,int length){int n=s.codePointCount(0,s.length());return n<=length?s:s.substring(0,s.offsetByCodePoints(0,length));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new AgentException("INVALID_ANALYSIS","Material encoding failed");}}
}
