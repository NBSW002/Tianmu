package com.tianmu.agent.feedback;

import java.util.*;

/** A presentation of accepted batch notes, never a semantic rewrite or a prefix of an unfinished statement. */
final class FeedbackTopicNotes {
    private FeedbackTopicNotes(){}
    static String present(List<String> notes,int maximum,String label){
        var unique=new LinkedHashSet<String>();
        for(String note:notes)if(!note.isBlank())unique.add(note.strip());
        String full=String.join("\n\n",unique);
        if(full.length()<=maximum)return full;
        return "本主题保留了 "+unique.size()+" 条不同的批次"+label+"，超过摘要展示长度。请展开报告中的完整观点记录；仅凭本段不能判断观众意见是否一致。";
    }
}
