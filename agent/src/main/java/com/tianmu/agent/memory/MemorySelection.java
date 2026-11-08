package com.tianmu.agent.memory;

import com.tianmu.agent.memory.PersonalMemory.*;
import java.util.*;

/** Deterministic selection. Memory content is data, not executable routing instructions. */
public final class MemorySelection {
    private MemorySelection(){}
    public static boolean core(String kind,String attribute,String topic,String qualifier){
        return kind.equals("PREFERENCE") && topic.isEmpty() && qualifier.isEmpty()
            && Set.of("response_length","explanation_style","response_language","response_format").contains(attribute);
    }
    public static String expand(String query){
        String out=query;
        if(query.matches("(?s).*(学校|上学|就读|大学|毕业).*"))out+=" 学校 大学 就读 毕业";
        if(query.matches("(?s).*(所在城市|住哪|附近|本地).*"))out+=" 城市 居住";
        return out;
    }
    public static Set<String> terms(String query){
        var result=new LinkedHashSet<String>();
        for(String term:MemoryKeywords.terms(query,128))if(term.codePointCount(0,term.length())>1 || term.codePoints().noneMatch(p->Character.UnicodeScript.of(p)==Character.UnicodeScript.HAN))result.add(term);
        return result;
    }
    public static int score(Entry entry,String query,String video){
        var c=entry.content();int score=0;
        if(c.scope().type()==ScopeType.VIDEO && Objects.equals(c.scope().key(),video))score+=100;
        var terms=terms(query);var content=terms(c.text());
        for(String term:terms)if(content.contains(term))score+=term.codePointCount(0,term.length())>2?4:2;
        return score;
    }
    public static List<Entry> rank(Collection<Entry> entries,String query,String video){
        return entries.stream().filter(e->score(e,query,video)>0)
            .sorted(Comparator.<Entry>comparingInt(e->score(e,query,video)).reversed()
                .thenComparing(Comparator.comparingLong(Entry::sequence).reversed()).thenComparing(e->e.id().toString())).toList();
    }
    public static List<Entry> fuse(List<Entry> lexical,List<Entry> semantic){
        var scores=new HashMap<UUID,Double>();var entries=new LinkedHashMap<UUID,Entry>();
        for(var list:List.of(lexical,semantic))for(int i=0;i<list.size();i++){
            var entry=list.get(i);entries.put(entry.id(),entry);scores.merge(entry.id(),1.0/(60+i+1),Double::sum);
        }
        return entries.values().stream().sorted(Comparator.<Entry>comparingDouble(e->scores.get(e.id())).reversed().thenComparing(Comparator.comparingLong(Entry::sequence).reversed()).thenComparing(e->e.id().toString())).toList();
    }
}
