package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Streaming bounded materialization. Exact grouping never discards members, authorship or reply context. */
public final class FeedbackPlanner {
    public record Planned(Snapshot snapshot,List<Blob> inputs) { public Planned {inputs=List.copyOf(inputs);} }
    private record Key(Source source,String text,String context,boolean author,Long at) {}
    private final FeedbackObjects objects;
    private final FeedbackPolicy policy;
    public FeedbackPlanner(FeedbackObjects objects,FeedbackPolicy policy){this.objects=objects;this.policy=policy;}
    public Collector collector(UUID job,Request request){return new Collector(job,request);}
    public Collector collector(UUID job,Request request,Map<String,String> candidates){var collector=new Collector(job,request);collector.candidates=Map.copyOf(candidates);return collector;}
    public final class Collector {
        private final UUID job; private final Request request;
        private Map<String,String> candidates=Map.of();
        private final List<Blob> chunks=new ArrayList<>(),inputs=new ArrayList<>();
        private final List<Unit> chunk=new ArrayList<>();private final LinkedHashMap<Key,List<Unit>> batch=new LinkedHashMap<>();
        private final Set<String> ids=new HashSet<>(),people=new HashSet<>();private final MessageDigest digest;
        private long bytes,comments,danmaku,authorComments,authorDanmaku;private int chunkBytes,batchBytes,sequence,batchRecords;
        Collector(UUID job,Request request){this.job=job;this.request=request;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
        public void accept(List<Unit> rows) {
            for(Unit row:rows) {
                if(!request.sources().contains(row.source()) || !ids.add(row.id()))throw new AgentException("INVALID_EVIDENCE","Unselected or duplicate snapshot member");
                if(row.createdAt()!=null && (row.createdAt().isBefore(request.query().start().atZone(DanmakuTypes.ZONE).toInstant()) || !row.createdAt().isBefore(request.query().end().atZone(DanmakuTypes.ZONE).toInstant())))throw new AgentException("INVALID_EVIDENCE","Snapshot member outside frozen time range");
                if(ids.size()>policy.maxRecords())throw new AgentException("FULL_ANALYSIS_TOO_LARGE","Feedback exceeds the configured record quota");
                int size=objects.bytes(row);bytes=Math.addExact(bytes,size);
                if(bytes>policy.maxSnapshotBytes())throw new AgentException("FULL_ANALYSIS_TOO_LARGE","Feedback snapshot byte quota exceeded");
                digest.update((row.id()+":"+size+":"+row.toString()+"\n").getBytes(StandardCharsets.UTF_8));
                if(chunkBytes+size>524288 && !chunk.isEmpty())flushChunk();chunk.add(row);chunkBytes+=size;
                if(row.source()==Source.COMMENT){comments++;if(row.author())authorComments++;}else{danmaku++;if(row.author())authorDanmaku++;}
                if(!row.author() && row.userId()!=null)people.add(row.userId());
                var key=new Key(row.source(),row.text(),row.replyContext(),row.author(),row.playbackMs());
                int visible=objects.bytes(Map.of("text",row.text(),"reply_context",row.replyContext(),"source",row.source(),"author",row.author()));
                if(visible>policy.batchBytes())throw new AgentException("FEEDBACK_UNIT_TOO_LARGE","A feedback item and its reply context exceed the bounded batch size");
                if((!batch.containsKey(key) && (batch.size()>=policy.batchGroups() || batchBytes+visible>policy.batchBytes())) || batchRecords>=1000)flushBatch();
                if(!batch.containsKey(key))batchBytes+=visible;batch.computeIfAbsent(key,k->new ArrayList<>()).add(row);batchRecords++;
            }
        }
        private void flushChunk(){if(!chunk.isEmpty()){chunks.add(objects.put(job,List.copyOf(chunk)));chunk.clear();chunkBytes=0;}}
        private void flushBatch(){
            if(batch.isEmpty())return;var groups=new ArrayList<Group>();
            for(var entry:batch.entrySet()) {var k=entry.getKey();var rows=entry.getValue();groups.add(new Group("G"+(++sequence),k.source(),k.text(),k.context(),k.author(),k.at(),rows.size(),rows.stream().map(Unit::id).toList(),candidates.get(rows.get(0).id())));}
            inputs.add(objects.put(job,new Input(WorkKind.CLASSIFY,groups,List.of(),null)));batch.clear();batchBytes=0;batchRecords=0;
        }
        public Planned finish(FeedbackSnapshotPort.Receipt receipt) {
            flushChunk();flushBatch();var counts=new Counts(comments,danmaku,authorComments,authorDanmaku,people.size());
            if(!counts.equals(receipt.counts()))throw new AgentException("INVALID_EVIDENCE","Snapshot counts differ from exported originals");
            return new Planned(new Snapshot("feedback-snapshot-v1",receipt.dataAsOf(),counts,chunks,HexFormat.of().formatHex(digest.digest())),inputs);
        }
    }
    public ResultNode validate(String workId,Input input,List<Proposal> proposals) {
        if(proposals==null || proposals.size()>(input.kind()==WorkKind.CLASSIFY?10:100))throw new AgentException("INVALID_ANALYSIS","Too many or missing topics");
        Map<String,long[]> members=new LinkedHashMap<>();
        if(input.kind()==WorkKind.CLASSIFY)for(Group g:input.groups()){
            if(g.messages()!=g.originalIds().size() || g.messages()<1 || members.put(g.id(),new long[]{g.messages(),g.author()?g.messages():0})!=null)throw new AgentException("STORAGE_CORRUPT","Invalid classification input");
        } else for(Topic t:input.children())if(members.put(t.id(),new long[]{t.messages(),t.authorMessages()})!=null)throw new AgentException("STORAGE_CORRUPT","Repeated child topic");
        var supplied=new HashSet<String>();var duplicates=new LinkedHashSet<String>();var unknown=new LinkedHashSet<String>();
        for(Proposal p:proposals)for(String id:p.members()) {
            if(!supplied.add(id))duplicates.add(id);
            if(!members.containsKey(id))unknown.add(id);
        }
        var missing=members.keySet().stream().filter(id->!supplied.contains(id)).toList();
        if(!missing.isEmpty() || !duplicates.isEmpty() || !unknown.isEmpty())
            throw new FeedbackCorrectionException(new Correction(proposals,missing,List.copyOf(duplicates),List.copyOf(unknown)));
        var representativeAssignments=new HashMap<String,String>();
        for(var group:input.groups())if(input.kind()==WorkKind.CLASSIFY && group.candidateCluster()!=null && group.id().equals(group.candidateCluster()+"_"+group.source().name())){
            var key=proposals.stream().filter(p->p.members().contains(group.id())).map(Proposal::key).findFirst().orElseThrow();
            var previous=representativeAssignments.putIfAbsent(group.candidateCluster(),key);
            if(previous!=null && !previous.equals(key))throw new FeedbackCorrectionException(new Correction(proposals,List.of(),List.of(),List.of(),"REPRESENTATIVE_CLUSTER_SPLIT"));
        }
        var topicBuckets=new HashMap<String,String>();
        for(var entry:representativeAssignments.entrySet())if(entry.getKey().matches("B[1-5]")){
            var previous=topicBuckets.putIfAbsent(entry.getValue(),entry.getKey());
            if(previous!=null && !previous.equals(entry.getKey()))throw new FeedbackCorrectionException(new Correction(proposals,List.of(),List.of(),List.of(),"REPRESENTATIVE_BUCKET_MERGED"));
        }
        for(Proposal p:proposals) {
            if(p.title()!=null && p.title().length()>100 || p.observation()!=null && p.observation().length()>2400 || p.recommendation()!=null && p.recommendation().length()>1200)
                throw new FeedbackCorrectionException(new Correction(proposals,List.of(),List.of(),List.of(),"TEXT_LIMIT_EXCEEDED"));
        }
        Set<String> covered=new HashSet<>(),keys=new HashSet<>();var topics=new ArrayList<Topic>();
        try {
            for(Proposal p:proposals){
                if(p.key()==null || !p.key().matches("T(?:[1-9]|[1-9][0-9]|100)") || !keys.add(p.key()) || p.members().isEmpty())throw new IllegalArgumentException();
                long count=0,authors=0;
                for(String id:p.members()){var weight=members.get(id);if(weight==null || !covered.add(id))throw new IllegalArgumentException();count=Math.addExact(count,weight[0]);authors=Math.addExact(authors,weight[1]);}
                topics.add(new Topic(workId+":"+p.key(),p.title(),p.observation(),p.recommendation(),p.members(),count,authors,p.selection()));
            }
            if(!covered.equals(members.keySet()))throw new IllegalArgumentException();
            return new ResultNode(workId,input.kind(),topics,topics.stream().mapToLong(Topic::messages).sum(),topics.stream().mapToLong(Topic::authorMessages).sum());
        } catch(IllegalArgumentException|ArithmeticException invalid){throw new AgentException("INVALID_ANALYSIS","Every batch group or child topic must be assigned exactly once with valid bounded observations");}
    }
}
