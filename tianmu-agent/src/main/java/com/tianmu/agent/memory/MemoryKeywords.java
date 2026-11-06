package com.tianmu.agent.memory;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded exact words and Han unigrams/bigrams; not a semantic retrieval substitute. */
public final class MemoryKeywords {
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}]+");
    private MemoryKeywords() {}
    public static List<String> terms(String value,int limit) {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        var matcher=WORD.matcher(Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while(matcher.find() && out.size()<limit) {
            int[] points=matcher.group().codePoints().toArray();
            if(points.length<=32)out.add(new String(points,0,points.length));
            for(int i=0;i<points.length && out.size()<limit;i++) {
                if(Character.UnicodeScript.of(points[i])==Character.UnicodeScript.HAN) {
                    out.add(new String(points,i,1));
                    if(i+1<points.length && Character.UnicodeScript.of(points[i+1])==Character.UnicodeScript.HAN && out.size()<limit)
                        out.add(new String(points,i,2));
                }
            }
        }
        return out.stream().limit(limit).toList();
    }
}
