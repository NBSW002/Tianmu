package com.tianmu.agent.feedback;

import com.tianmu.agent.feedback.FeedbackModels.*;
import java.util.*;
import java.util.regex.Pattern;

/** Presentation gate, not a general fact checker. Dataset statistics belong to the host ledger. */
public final class FeedbackNarrativePolicy {
    private FeedbackNarrativePolicy() {}
    private static final String NUMBER="[0-9零〇一二两三四五六七八九十百千万]+";
    private static final Pattern COUNTS=Pattern.compile(
            "(?:作者|观众|用户|反馈|弹幕|评论|消息|记录)(?:的)?(?:发言|评论|弹幕|消息|反馈|记录|人数|数量)?(?:一共|总共|共有|共计|合计|共|有|为|是|仅有|仅|约|至少|超过|不足|不到|近|达|达到)?[\\s:：]*"+NUMBER+"(?:条|人|次)"
            +"|"+NUMBER+"条(?:均|都|全部)(?:为|是)?(?:作者|观众)"
            +"|(?:共计|合计|共有|共|总计|一共)"+NUMBER+"条(?:评论|弹幕|反馈|消息|记录|发言)"
            +"|(?:本次|本批|本批次|这批|本报告|反馈样本)(?:共有|包含|包括|覆盖|涉及|处理|一共|合计|共|有|计)?"+NUMBER+"条"
            +"|(?:占比|好评率|负面率|正面率)(?:为|是|约|达到|达)?[\\s:：]*"+NUMBER+"(?:\\.[0-9]+)?[%％]"
            +"|"+NUMBER+"(?:\\.[0-9]+)?[%％]的(?:观众|用户|评论|弹幕|反馈)"
            +"|百分之"+NUMBER+"的(?:观众|用户|评论|弹幕|反馈)");
    private static final Pattern REFERENCES=Pattern.compile(
            "(?<![A-Za-z0-9_])(?:[GCKT][1-9][0-9]*|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}:T[1-9][0-9]*)(?![A-Za-z0-9_])");
    private static final Pattern QUOTE=Pattern.compile("“([^”]*)”|「([^」]*)」|\"([^\"]*)\"");

    public static void validate(Input input,List<Proposal> proposals){
        String corpus=corpus(input);
        for(var proposal:proposals){
            for(String text:List.of(proposal.title(),proposal.observation(),proposal.recommendation())){
                String generated=withoutVerifiedQuotes(text,corpus);
                if(COUNTS.matcher(generated).find())reject(proposals,"NARRATIVE_DATASET_COUNTS");
                var refs=REFERENCES.matcher(generated);
                while(refs.find()){
                    // G1 can be a real device model. Only source-supported identifiers are exempt.
                    String token=refs.group();
                    if(!Pattern.compile("(?<![A-Za-z0-9_])"+Pattern.quote(token)+"(?![A-Za-z0-9_])").matcher(corpus).find())
                        reject(proposals,"NARRATIVE_INTERNAL_REFERENCE");
                }
            }
        }
    }
    private static String corpus(Input input){
        var text=new StringBuilder();
        input.groups().forEach(g->text.append(g.text()).append('\n').append(g.replyContext()).append('\n'));
        input.groups().forEach(g->g.replies().forEach(r->text.append(r.text()).append('\n')));
        input.children().forEach(t->text.append(t.title()).append('\n').append(t.observation()).append('\n').append(t.recommendation()).append('\n'));
        if(input.video()!=null){text.append(input.video().summary()).append('\n').append(input.video().transcript());input.video().moments().forEach(m->text.append('\n').append(m.observation()));}
        return text.toString();
    }
    private static String withoutVerifiedQuotes(String text,String corpus){
        var quotes=QUOTE.matcher(text);var clean=new StringBuilder();int end=0;
        while(quotes.find()){
            clean.append(text,end,quotes.start());
            String content=quotes.group(1)!=null?quotes.group(1):quotes.group(2)!=null?quotes.group(2):quotes.group(3);
            clean.append(!content.isBlank() && corpus.contains(content)?" ":quotes.group());end=quotes.end();
        }
        return clean.append(text,end,text.length()).toString();
    }
    private static void reject(List<Proposal> proposals,String issue){
        throw new FeedbackCorrectionException(new Correction(proposals,List.of(),List.of(),List.of(),issue));
    }
}
