package com.tianmu.agent.feedback;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.feedback.FeedbackModels.Group;
import java.text.Normalizer;
import java.util.*;

/** Presentation projection of accepted evidence. It never reclassifies or changes the membership ledger. */
public record FeedbackReportDocument(Asset asset,List<Card> cards,List<Topic> notes,Map<String,Long> daily) {
    public record Quote(String text,Source source,boolean author,Long playbackMs,String replyContext) {}
    public record Card(Topic topic,long comments,long danmaku,List<Quote> quotes,List<VideoContent.Moment> moments) {}
    public FeedbackReportDocument {cards=List.copyOf(cards);notes=List.copyOf(notes);daily=Collections.unmodifiableMap(new TreeMap<>(daily));}

    public static FeedbackReportDocument build(Asset asset,List<Topic> roots,Map<String,Topic> graph,
            Map<String,List<Group>> leaves,Map<String,Long> daily) {
        var cards=new ArrayList<Card>();var shown=new ArrayList<String>();var allOriginals=new HashSet<String>();
        var ordered=roots.stream().sorted(Comparator.comparingLong((Topic t)->t.messages()-t.authorMessages()).reversed()).toList();
        for(var root:ordered){
            var groups=new ArrayList<Group>();var queue=new ArrayDeque<Topic>();var seen=new HashSet<String>();queue.add(root);
            while(!queue.isEmpty()){
                var topic=queue.remove();if(!seen.add(topic.id()))throw corrupt();
                if(leaves.containsKey(topic.id()))groups.addAll(leaves.get(topic.id()));
                else for(String id:topic.members()){var child=graph.get(id);if(child==null)throw corrupt();queue.add(child);}
            }
            long comments=0,danmaku=0,authors=0;var originals=new HashSet<String>();
            for(var g:groups){
                if(g.messages()!=g.originalIds().size())throw corrupt();
                for(String id:g.originalIds())if(!originals.add(id) || !allOriginals.add(id))throw corrupt();
                if(g.source()==Source.COMMENT)comments+=g.messages();else danmaku+=g.messages();
                if(g.author())authors+=g.messages();
            }
            if(comments+danmaku!=root.messages() || authors!=root.authorMessages())throw corrupt();
            var theme=grams(normalize(root.title()+root.observation()));
            var candidates=groups.stream().filter(g->!g.text().isBlank()).toList();
            if(candidates.stream().anyMatch(g->!g.author()))candidates=candidates.stream().filter(g->!g.author()).toList();
            if(candidates.stream().anyMatch(g->g.text().codePointCount(0,g.text().length())<=180))
                candidates=candidates.stream().filter(g->g.text().codePointCount(0,g.text().length())<=180).toList();
            var remaining=new ArrayList<>(candidates);var quotes=new ArrayList<Quote>();var covered=new HashSet<String>();
            while(!remaining.isEmpty() && quotes.size()<3){
                remaining.sort(Comparator.<Group>comparingDouble(g->score(g.text(),theme,covered)).reversed()
                        .thenComparing(Comparator.comparingLong(Group::messages).reversed()).thenComparingInt(g->g.text().length()));
                var g=remaining.remove(0);String key=normalize(g.text());
                if(key.isEmpty() || shown.stream().anyMatch(old->similar(old,key)))continue;
                shown.add(key);covered.addAll(grams(key));quotes.add(new Quote(g.text(),g.source(),g.author(),g.playbackMs(),g.replyContext()));
            }
            // A nearby sampled frame provides context, not verification of the feedback's claim.
            var moments=new LinkedHashMap<Long,VideoContent.Moment>();
            if(asset.video()!=null)for(var q:quotes)if(q.source()==Source.DANMAKU && q.playbackMs()!=null
                    && q.playbackMs()>=asset.video().startMs() && q.playbackMs()<asset.video().endMs()){
                asset.video().moments().stream().min(Comparator.comparingLong(m->Math.abs(m.atMs()-q.playbackMs())))
                        .ifPresent(m->moments.putIfAbsent(m.atMs(),m));
                if(moments.size()==2)break;
            }
            cards.add(new Card(root,comments,danmaku,List.copyOf(quotes),List.copyOf(moments.values())));
        }
        if(cards.stream().mapToLong(Card::comments).sum()!=asset.snapshot().counts().comments()
                || cards.stream().mapToLong(Card::danmaku).sum()!=asset.snapshot().counts().danmaku()
                || cards.stream().mapToLong(c->c.topic().authorMessages()).sum()!=asset.snapshot().counts().authorComments()+asset.snapshot().counts().authorDanmaku())throw corrupt();
        var notes=graph.values().stream().filter(t->leaves.containsKey(t.id())).toList();
        return new FeedbackReportDocument(asset,cards,notes,daily);
    }
    private static AgentException corrupt(){return new AgentException("STORAGE_CORRUPT","Report topics do not match accepted source membership");}
    private static String normalize(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+","");}
    private static Set<String> grams(String s){var out=new HashSet<String>();for(int i=0;i+1<s.length();i++)out.add(s.substring(i,i+2));return out;}
    private static double score(String text,Set<String> theme,Set<String> covered){var words=grams(normalize(text));int size=words.size();words.retainAll(theme);double relevant=words.size();words.removeAll(covered);return size==0?0:(relevant+words.size())/Math.sqrt(size);}
    private static boolean similar(String a,String b){
        if(a.equals(b))return true;if(Math.min(a.length(),b.length())<12)return false;
        var x=grams(a);var y=grams(b);var union=new HashSet<>(x);union.addAll(y);x.retainAll(y);
        return !union.isEmpty() && x.size()/(double)union.size()>=.82;
    }
}
