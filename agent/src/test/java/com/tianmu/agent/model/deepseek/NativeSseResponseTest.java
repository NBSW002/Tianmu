package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSseResponseTest {
    ObjectMapper json=new ObjectMapper();
    @Test void reasoningIsCollectedAcrossChunksButNeverSentToVisibleBody()throws Exception {
        var visible=new ArrayList<String>();var parser=new NativeSseResponse(json,visible::add);
        parser.accept(chunk(Map.of("reasoning_content","先核对"),null));
        parser.accept(chunk(Map.of("reasoning_content","数据来源。"),null));
        parser.accept(chunk(Map.of("content","这是正文。"),"stop"));parser.accept("[DONE]");
        var message=parser.result().path("choices").get(0).path("message");
        assertEquals("先核对数据来源。",message.path("reasoning_content").asText());
        assertEquals("这是正文。",String.join("",visible));
    }
    String chunk(Object delta,String finish)throws Exception {var choice=new HashMap<String,Object>();choice.put("index",0);choice.put("delta",delta);choice.put("finish_reason",finish);return json.writeValueAsString(Map.of("choices",List.of(choice)));}
    @Test void combinesUnicodeAndUsageWithBoundedDisplayEvents()throws Exception {
        var output=new ArrayList<String>();var parser=new NativeSseResponse(json,output::add);
        for(int i=0;i<1000;i++)parser.accept(chunk(Map.of("content","你"),null));
        parser.accept(chunk(Map.of(),"stop"));parser.accept("{\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1000}}");parser.accept("[DONE]");
        assertEquals("你".repeat(1000),String.join("",output));assertTrue(output.size()<30);assertEquals(1000,parser.result().path("usage").path("completion_tokens").asInt());
    }
    @Test void joinsNativeToolArgumentsWithoutDisplayingThem()throws Exception {
        var out=new ArrayList<String>();var parser=new NativeSseResponse(json,out::add);
        parser.accept(chunk(Map.of("tool_calls",List.of(Map.of("index",0,"id","call-1","function",Map.of("name","search","arguments","{\"q\":")))),null));
        parser.accept(chunk(Map.of("tool_calls",List.of(Map.of("index",0,"function",Map.of("arguments","\"你好\"}")))),"tool_calls"));parser.accept("[DONE]");
        assertEquals("{\"q\":\"你好\"}",parser.result().path("choices").get(0).path("message").path("tool_calls").get(0).path("function").path("arguments").asText());assertTrue(out.isEmpty());
    }
    @Test void incompleteStreamAndSparseToolsNeverBecomeFinalAnswer()throws Exception {
        var parser=new NativeSseResponse(json,s->{});parser.accept(chunk(Map.of("content","partial"),null));assertThrows(AgentException.class,parser::result);
        parser.accept("[DONE]");assertThrows(AgentException.class,parser::result);
        var sparse=new NativeSseResponse(json,s->{});sparse.accept(chunk(Map.of("tool_calls",List.of(Map.of("index",2,"id","c","function",Map.of("name","x","arguments","{}")))),"tool_calls"));sparse.accept("[DONE]");assertThrows(AgentException.class,sparse::result);
    }
    @Test void structuredLegacyResponsesAreNotShownAsRawJson()throws Exception {
        var out=new ArrayList<String>();var parser=new NativeSseResponse(json,out::add);parser.accept(chunk(Map.of("content"," {\"kind\":\"ANSWER\"}"),"stop"));parser.accept("[DONE]");assertTrue(out.isEmpty());assertFalse(parser.result().isMissingNode());
    }
}
