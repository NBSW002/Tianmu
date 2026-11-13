package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackCompression.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackDiscussionMaterialsTest {
    private final ObjectMapper json=new ObjectMapper();
    private Unit row(int id,String text,String context){return new Unit("C:"+id,Source.COMMENT,""+id,text,context,false,null,"viewer",Instant.now(),0L);}
    @Test void attachesOnlyTrueDescendantsAndPreservesCountsAndThreeQuoteLimit(){
        var root=row(1,"小美人唱得太好了","");
        var reply=row(2,"IP错误","[{\"id\":\"1\",\"author\":false,\"text\":\"小美人唱得太好了\"}]");
        var nested=row(3,"这是接梗回复","[{\"id\":\"2\",\"author\":false,\"text\":\"IP错误\"},{\"id\":\"1\",\"author\":false,\"text\":\"小美人唱得太好了\"}]");
        var orphan=row(4,"同样的主评论文字","[{\"id\":\"99\",\"parentUnavailable\":true}]");
        var compression=new FeedbackCompression(null,null,FeedbackCompressionProperties.defaults(),json);
        compression.tieredPolicy(new FeedbackTieredProperties(true,32768));
        var control=new com.tianmu.agent.runtime.control.CallControl(Instant.now().plusSeconds(10),new com.tianmu.agent.runtime.control.CancellationToken());
        var parts=List.of(compression.prepare(Source.COMMENT,List.of(root,reply,nested,orphan),control));
        var blob=new Blob("fb_"+"0".repeat(32)+".json",1,"0".repeat(64));
        var material=compression.directMaterial(blob,new Counts(4,0,0,0,1),parts,control);
        assertEquals(1,material.representativeCount());
        var result=FeedbackDiscussionMaterials.attach(material,parts,json);
        var group=result.inputs().get(0).groups().get(0);
        assertEquals(List.of("C:1","C:2","C:3"),group.originalIds());assertEquals(3,group.messages());
        assertEquals("C:2",group.replies().get(1).parentId());
        assertEquals(1L,FeedbackRerankConfirmation.modelEvidence(group).get("exactMessages"));
        assertEquals(List.of("C:4"),result.unclassified());
        assertEquals(4,result.clusters().stream().mapToInt(c->c.members().size()).sum()+result.unclassified().size());
        var selection=new Selection(List.of("C:1"),List.of(),"COHERENT",true,"confirmed");
        var proposal=new FeedbackModels.Proposal("T1","评论区玩梗","观众在接梗。","",List.of("C:1"),selection);
        assertEquals(3,FeedbackRerankConfirmation.validate("work",result.inputs().get(0),List.of(proposal)).covered());
        assertEquals(2,result.clusters().get(0).representatives().get(0).replies().size());
    }
}
