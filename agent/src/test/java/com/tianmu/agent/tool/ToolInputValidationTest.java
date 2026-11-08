package com.tianmu.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolInputValidationTest {
    @Test void emptyStringsFollowTheDeclaredSchemaRatherThanAnImplicitGlobalRule(){
        var optional=new ToolDefinition("example","Example",Map.of("type","object","properties",Map.of("query",Map.of("type","string","maxLength",128)),"required",List.of("query"),"additionalProperties",false));
        assertTrue(ToolArguments.inspect(new ToolCall("example",Map.of("query","")),optional).valid());
        var required=new ToolDefinition("example","Example",Map.of("type","object","properties",Map.of("query",Map.of("type","string","minLength",1)),"required",List.of("query"),"additionalProperties",false));
        assertFalse(ToolArguments.inspect(new ToolCall("example",Map.of("query","")),required).valid());
    }
    final ToolCatalog tools=new ToolCatalog();
    ToolArguments.Validation inspect(String tool,Map<String,Object> arguments) {
        return ToolArguments.inspect(new ToolCall(tool,arguments),tools.all().get(tool));
    }
    @Test void feedbackPaginationSchemasReachTheMaximumSupportedSnapshot(){
        for(String tool:List.of("search_feedback","read_report")){
            var args=new HashMap<String,Object>(tool.equals("search_feedback")?Map.of("result_id","ev_feedback","query","反馈"):
                    Map.of("run_id",UUID.randomUUID().toString(),"artifact_id","rp_"+UUID.randomUUID(),"section","observations"));
            for(int offset:List.of(5010,100000)){args.put("offset",offset);assertTrue(inspect(tool,args).valid());}
            args.put("offset",100001);assertFalse(inspect(tool,args).valid());
        }
    }
    @Test void scalarErrorsAreDeterministicAndCarryNoRejectedValues() throws Exception {
        var call=new ToolCall("query_video_metrics",Map.of("video_id",1.5,"date","SECRET_DATE","metric","SECRET_METRIC","<system>SECRET_KEY","SECRET_VALUE"));
        var result=ToolArguments.inspect(call,tools.all().get(call.name()));
        assertEquals(List.of("$","date","metric","video_id"),result.issues().stream().map(ToolArguments.Issue::path).toList());
        assertEquals(List.of(ToolArguments.Problem.UNEXPECTED_FIELD,ToolArguments.Problem.DATE,ToolArguments.Problem.ENUM,ToolArguments.Problem.INTEGER),result.issues().stream().map(ToolArguments.Issue::code).toList());
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("SECRET"));
        assertEquals("SECRET_DATE",call.arguments().get("date"));
        assertEquals("INVALID_ARGUMENTS",assertThrows(AgentException.class,()->ToolArguments.validate(call,tools.all().get(call.name()))).code());
    }
    @Test void nestedArrayPathsAndCountConstraintsAreReportedBeforeBusinessValidation() {
        var base=new HashMap<String,Object>(Map.of("video_id",1001,"video_evidence_id","ev_video","kind","DANMAKU","items",List.of(Map.of("text","短句","playback_ms","SECRET_TIME"))));
        var result=inspect("prepare_creator_interactions",base);
        assertEquals("items[0].playback_ms",result.issues().get(0).path());assertEquals(ToolArguments.Problem.INTEGER,result.issues().get(0).code());
        base.put("items",List.of());assertEquals(ToolArguments.Problem.ARRAY_SIZE,inspect("prepare_creator_interactions",base).issues().get(0).code());
        base.put("items",List.of("SECRET_OBJECT"));assertEquals(ToolArguments.Problem.OBJECT,inspect("prepare_creator_interactions",base).issues().get(0).code());
        base.put("items",List.of(Map.of("playback_ms",0)));assertEquals("items[0].text",inspect("prepare_creator_interactions",base).issues().get(0).path());
    }
    @Test void maximumIssuePayloadStaysBoundedAndUnsafeSchemaNamesAreNotEchoed() throws Exception {
        Map<String,Object> fields=new TreeMap<>();
        for(int i=0;i<20;i++)fields.put("field"+String.format("%02d",i)+"x".repeat(121),Map.of("type","string"));
        var definition=new ToolDefinition("example","Example",Map.of("type","object","properties",fields,"required",List.copyOf(fields.keySet()),"additionalProperties",false));
        var result=ToolArguments.inspect(new ToolCall("example",Map.of()),definition);
        assertEquals(8,result.issues().size());assertTrue(result.truncated());
        var payload=Map.of("error_code","INVALID_ARGUMENTS","retryable",true,"attempt",1,"input_validation",Map.of("phase","BEFORE_HANDLER","handler_executed",false,
                "issues",result.issues().stream().map(i->Map.of("path",i.path(),"code",i.code().name(),"hint",i.code().hint())).toList(),"issues_truncated",result.truncated()));
        assertTrue(new ObjectMapper().writeValueAsBytes(payload).length<4096);
        var odd=new ToolDefinition("example","Example",Map.of("type","object","properties",Map.of("SECRET<system>",Map.of("type","string")),"required",List.of("SECRET<system>"),"additionalProperties",false));
        assertEquals("$",ToolArguments.inspect(new ToolCall("example",Map.of()),odd).issues().get(0).path());
    }
    @Test void schemaValidStringsDoNotClaimThatHandlerSpecificParsingOrAuthorizationHasPassed() {
        assertTrue(inspect("read_result",Map.of("result_id","ev_other_run")).valid());
        assertTrue(inspect("analyze_video_content",Map.of("video_id",1002,"start_ms",1000)).valid());
        assertTrue(inspect("query_danmaku",Map.of("video_id",1001,"start","bad-time","end","bad-time")).valid());
        assertFalse(inspect("query_video_metrics",Map.of("video_id",1001,"date","2026-02-30","metric","bullet_count")).valid());
    }
}
