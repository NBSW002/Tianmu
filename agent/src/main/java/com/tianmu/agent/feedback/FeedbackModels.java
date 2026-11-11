package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;

/** Workers have no arbitrary tools. Semantic decisions and deterministic validation are separate. */
public interface FeedbackModels {
    record Group(String id, Source source, String text, String replyContext, boolean author, Long playbackMs,
                 long messages, List<String> originalIds,String candidateCluster) {
        public Group(String id,Source source,String text,String replyContext,boolean author,Long playbackMs,long messages,List<String> originalIds){this(id,source,text,replyContext,author,playbackMs,messages,originalIds,null);}
        public Group { originalIds = List.copyOf(originalIds); }
    }
    record Correction(List<Proposal> previousTopics,List<String> missing,List<String> duplicates,List<String> unknown,String issue,int round) {
        public Correction(List<Proposal> previousTopics,List<String> missing,List<String> duplicates,List<String> unknown,String issue){this(previousTopics,missing,duplicates,unknown,issue,0);}
        public Correction(List<Proposal> previousTopics,List<String> missing,List<String> duplicates,List<String> unknown){this(previousTopics,missing,duplicates,unknown,"MEMBERSHIP_MISMATCH");}
        public Correction { previousTopics=List.copyOf(previousTopics);missing=List.copyOf(missing);duplicates=List.copyOf(duplicates);unknown=List.copyOf(unknown);if(round<0 || round>1000)throw new IllegalArgumentException("Invalid correction round"); }
    }
    /** Classification context, not evidence: the first accepted title and cumulative message count. */
    record KnownTopic(String key,String title,long messages) {}
    record Input(WorkKind kind, List<Group> groups, List<Topic> children, VideoContent.Facts video,Correction correction,
                 @com.fasterxml.jackson.annotation.JsonIgnore List<KnownTopic> knownTopics,
                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) boolean rerankDegraded) {
        public Input(WorkKind kind,List<Group> groups,List<Topic> children,VideoContent.Facts video){this(kind,groups,children,video,null);}
        public Input(WorkKind kind,List<Group> groups,List<Topic> children,VideoContent.Facts video,Correction correction){this(kind,groups,children,video,correction,null);}
        public Input(WorkKind kind,List<Group> groups,List<Topic> children,VideoContent.Facts video,Correction correction,List<KnownTopic> knownTopics){
            this(kind,groups,children,video,correction,knownTopics,false);
        }
        public Input {
            groups = List.copyOf(groups); children = List.copyOf(children);
            // Old serialized inputs may contain full prior topics. New retry inputs do not persist derived context.
            knownTopics=knownTopics==null?(kind==WorkKind.CLASSIFY?children.stream().map(t->new KnownTopic(t.id(),t.title(),t.messages())).toList():List.of()):List.copyOf(knownTopics);
        }
    }
    record Proposal(String key, String title, String observation, String recommendation, List<String> members,Selection selection) {
        public Proposal(String key,String title,String observation,String recommendation,List<String> members){this(key,title,observation,recommendation,members,null);}
        public Proposal { members = List.copyOf(members); }
    }
    String modelName();
    String policyHash();
    List<Proposal> analyze(Input input, CallControl control);
    default String pipelinePolicyHash(){return policyHash();}
    default List<Proposal> analyzePipeline(Input input,CallControl control){return analyze(input,control);}
    default boolean representativeReviewEnabled(){return false;}
    default List<Proposal> confirmReranked(Input input,CallControl control){return List.of();}
    default String rerankConfirmationHash(){return pipelinePolicyHash();}
}
