package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackCompression.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import java.util.*;

/** Attach only snapshot replies with a verified ancestor ID, after ranking root comments. */
final class FeedbackDiscussionMaterials {
    private FeedbackDiscussionMaterials() {}
    static boolean isReply(Unit row){return row.source()==Source.COMMENT && !row.replyContext().isBlank();}
    static Material attach(Material material,List<SourceMaterial> parts,ObjectMapper json){
        var children=new HashMap<String,List<Reply>>();
        for(var part:parts)for(var row:part.eligible()){
            if(!isReply(row))continue;
            try{
                var chain=json.readTree(row.replyContext());
                if(!chain.isArray() || chain.isEmpty())continue;
                var root=chain.get(chain.size()-1);String rootId=root.path("id").asText();
                String parent=chain.get(0).path("id").asText();
                if(!rootId.matches("[1-9][0-9]{0,18}") || !parent.matches("[1-9][0-9]{0,18}") || root.path("parentUnavailable").asBoolean())continue;
                String text=row.text();boolean excerpt=text.codePointCount(0,text.length())>512;
                if(excerpt)text=text.substring(0,text.offsetByCodePoints(0,512));
                children.computeIfAbsent("C:"+rootId,k->new ArrayList<>()).add(new Reply(row.id(),"C:"+parent,text,row.likeCount(),excerpt));
            }catch(java.io.IOException malformed){/* Unresolvable relationships remain unclassified. */}
        }
        var attached=new HashSet<String>();var enriched=new HashMap<String,Group>();var inputs=new ArrayList<Input>();
        for(var input:material.inputs()){
            var groups=new ArrayList<Group>();
            for(var group:input.groups()){
                var candidates=new ArrayList<Reply>();for(var id:group.originalIds())candidates.addAll(children.getOrDefault(id,List.of()));
                candidates.sort(Comparator.<Reply,Long>comparing(r->r.likes()==null?-1L:r.likes(),Comparator.reverseOrder()).thenComparing(Reply::id));
                var unique=new LinkedHashMap<String,Reply>();
                for(var reply:candidates)if(!attached.contains(reply.id()))unique.putIfAbsent(reply.text(),reply);
                var replies=unique.values().stream().limit(5).toList();var ids=new ArrayList<>(group.originalIds());
                for(var reply:replies){ids.add(reply.id());attached.add(reply.id());}
                var next=new Group(group.id(),group.source(),group.text(),group.replyContext(),group.author(),group.playbackMs(),ids.size(),ids,group.candidateCluster(),group.likes(),replies);
                groups.add(next);enriched.put(next.id(),next);
            }
            inputs.add(new Input(input.kind(),groups,input.children(),input.video(),input.correction(),input.knownTopics(),input.rerankDegraded()));
        }
        var clusters=new ArrayList<Cluster>();
        for(var cluster:material.clusters()){
            var ids=new LinkedHashSet<>(cluster.members());var representatives=new ArrayList<Representative>();
            for(var rep:cluster.representatives()){
                var group=enriched.get(rep.id());var replies=group==null?List.<Reply>of():group.replies();replies.forEach(r->ids.add(r.id()));
                representatives.add(new Representative(rep.id(),rep.source(),rep.text(),rep.context(),rep.playbackMs(),rep.likes(),rep.exactMessages(),rep.excerpt(),rep.textProvenance(),rep.contextProvenance(),replies));
            }
            clusters.add(new Cluster(cluster.id(),List.copyOf(ids),representatives));
        }
        return new Material(material.version(),material.policy(),material.protocolRevision(),material.manifest(),material.rawCounts(),material.eligibleCounts(),material.excluded(),material.exactExpressions(),clusters,inputs,material.representativeCount(),material.modelBytes(),material.unclassified().stream().filter(id->!attached.contains(id)).toList(),material.unclassifiedExamples().stream().filter(r->!attached.contains(r.id())).toList(),material.bucketMetric(),material.stats());
    }
}
