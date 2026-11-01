package com.tianmu.agent.feedback;

import com.tianmu.agent.core.FeedbackAnalysis.Blob;
import java.util.*;

/** Tracks even uncommitted work objects so failed attempts do not leave permanent private files. */
public interface FeedbackObjectIndex {
    record Expired(String key,UUID job){}
    void register(UUID job,Blob blob);
    List<Expired> expired(int limit);
    void removed(String key);
    FeedbackObjectIndex NONE=new FeedbackObjectIndex(){
        public void register(UUID job,Blob blob){}
        public List<Expired> expired(int limit){return List.of();}
        public void removed(String key){}
    };
}
