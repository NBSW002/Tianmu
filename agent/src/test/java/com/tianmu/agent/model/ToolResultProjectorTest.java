package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolResultProjectorTest {
    ObjectMapper json=new ObjectMapper();
    ArrayNode results(){var all=json.createArrayNode();for(int i=0;i<6;i++)all.add(json.valueToTree(Map.of("tool","analyze_comments","evidenceId","ev_"+i,"data",Map.of("total",123,"content","大段工具原文".repeat(2000)))));return all;}
    @Test void onlyCompactsOlderOwnedEvidenceAndKeepsRecentResults(){var original=results();var copy=original.deepCopy();assertEquals(2,new ToolResultProjector(json).compact(copy,true));assertEquals(123,copy.get(0).path("data").path("facts").path("total").asInt());assertEquals("ev_0",copy.get(0).path("data").path("readback").path("arguments").path("result_id").asText());assertFalse(copy.get(0).path("data").path("complete").asBoolean());assertEquals(original.get(5),copy.get(5));assertTrue(original.get(0).path("data").has("content"));}
    @Test void cannotExternalizeWithoutReadbackOrForErrors(){var values=results();assertEquals(0,new ToolResultProjector(json).compact(values,false));((ObjectNode)values.get(0).path("data")).put("error_code","INVALID_ARGUMENTS");((ObjectNode)values.get(1)).remove("evidenceId");assertEquals(0,new ToolResultProjector(json).compact(values,true));}
}
