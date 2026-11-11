package com.tianmu.agent.feedback;

import java.text.Normalizer;

/** Conservative screening of pure cheers; substantive text around cheers is retained. */
final class FeedbackTextQuality {
    private FeedbackTextQuality() {}
    static boolean lowInformation(String text) {
        String value=Normalizer.normalize(text,Normalizer.Form.NFKC)
                .replaceAll("\\[[^\\[\\]\\s]{1,30}\\]", "")
                .replaceAll("[\\p{P}\\p{Z}\\s]", "");
        int[] points=value.codePoints().toArray();
        if(points.length==0)return true;
        if(points.length>=3 && java.util.Arrays.stream(points).allMatch(p->p==points[0]))return true;
        return value.matches("(?i)(?:6{2,}|8{2,}|9{2,}|233+|[hw]{3,}|(?:哈|呵|啊|哦|噢|哇|嘻|嘿){3,})");
    }
}
