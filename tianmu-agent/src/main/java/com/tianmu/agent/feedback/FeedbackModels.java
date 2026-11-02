package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;

/** Workers have no arbitrary tools. Semantic decisions and deterministic validation are separate. */
public interface FeedbackModels {
    record Group(String id, Source source, String text, String replyContext, boolean author, Long playbackMs,
                 long messages, List<String> originalIds) {
        public Group { originalIds = List.copyOf(originalIds); }
    }
    record Correction(List<Proposal> previousTopics,List<String> missing,List<String> duplicates,List<String> unknown,String issue) {
        public Correction(List<Proposal> previousTopics,List<String> missing,List<String> duplicates,List<String> unknown){this(previousTopics,missing,duplicates,unknown,"MEMBERSHIP_MISMATCH");}
        public Correction { previousTopics=List.copyOf(previousTopics);missing=List.copyOf(missing);duplicates=List.copyOf(duplicates);unknown=List.copyOf(unknown); }
    }
    record Input(WorkKind kind, List<Group> groups, List<Topic> children, VideoContent.Facts video,Correction correction) {
        public Input(WorkKind kind,List<Group> groups,List<Topic> children,VideoContent.Facts video){this(kind,groups,children,video,null);}
        public Input { groups = List.copyOf(groups); children = List.copyOf(children); }
    }
    record Proposal(String key, String title, String observation, String recommendation, List<String> members) {
        public Proposal { members = List.copyOf(members); }
    }
    String modelName();
    String policyHash();
    List<Proposal> analyze(Input input, CallControl control);
}
