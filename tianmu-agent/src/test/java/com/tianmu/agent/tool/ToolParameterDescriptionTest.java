package com.tianmu.agent.tool;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tianmu.agent.core.AgentTypes.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolParameterDescriptionTest {
    final ObjectMapper json=new ObjectMapper();
    ToolDefinition definition() {
        var element=Map.of("type","object","properties",Map.of("at",Map.of("type","integer","minimum",0,"maximum",5,"description","Playback milliseconds")),
                "required",List.of("at"),"additionalProperties",false);
        return new ToolDefinition("example","Example",Map.of("type","object","properties",Map.of(
                "date",Map.of("type","string","format","date","description","A completed YYYY-MM-DD day"),
                "items",Map.of("type","array","items",element,"minItems",1,"maxItems",2,"description","Native object array")),
                "required",List.of("date","items"),"additionalProperties",false));
    }
    ToolDefinition copy(JsonNode value) {return json.convertValue(value,ToolDefinition.class);}
    ObjectNode fields(ObjectNode value) {return (ObjectNode)value.path("inputSchema").path("properties");}
    JsonNode stripDescriptions(JsonNode node) {
        var result=node.deepCopy();
        if(result instanceof ObjectNode object) {object.remove("description");object.elements().forEachRemaining(child->{
            if(child instanceof ObjectNode || child instanceof ArrayNode) stripInPlace(child);
        });}
        return result;
    }
    void stripInPlace(JsonNode node) {
        if(node instanceof ObjectNode object)object.remove("description");
        node.elements().forEachRemaining(this::stripInPlace);
    }
    @Test void descriptionsDoNotChangeArgumentAdmissionOrAllowMetadataAsAnArgument() {
        var described=definition();ToolCatalog.validate(described);
        var plain=new ToolDefinition(described.name(),described.description(),json.convertValue(stripDescriptions(json.valueToTree(described.inputSchema())),Map.class));
        ToolCatalog.validate(plain);
        List<Map<String,Object>> cases=List.of(
                Map.of("date","2026-10-31","items",List.of(Map.of("at",3))),
                Map.of("date","bad","items",List.of(Map.of("at",3))),
                Map.of("items",List.of(Map.of("at",3))),
                Map.of("date","2026-10-31","items",List.of()),
                Map.of("date","2026-10-31","items",List.of(Map.of("at",6))),
                Map.of("date","2026-10-31","items",List.of(Map.of("at","3"))),
                Map.of("date","2026-10-31","items",List.of(Map.of("at",3,"description","fake"))));
        for(int i=0;i<cases.size();i++) {
            var call=new ToolCall("example",cases.get(i));
            boolean expected=i==0;
            for(var schema:List.of(plain,described)) {
                if(expected)assertDoesNotThrow(()->ToolArguments.validate(call,schema));
                else assertThrows(RuntimeException.class,()->ToolArguments.validate(call,schema));
            }
        }
    }
    @Test void onlyBoundedNonblankTextIsAcceptedAtEverySupportedFieldLocation() {
        for(String field:List.of("date","items","at"))for(Object invalid:List.of(""," ","x".repeat(513),42,List.of("text"))) {
            ObjectNode value=json.valueToTree(definition());
            ObjectNode target=field.equals("at")?(ObjectNode)fields(value).path("items").path("items").path("properties").path("at"):(ObjectNode)fields(value).get(field);
            target.set("description",json.valueToTree(invalid));
            assertThrows(IllegalStateException.class,()->ToolCatalog.validate(copy(value)));
            target.putNull("description");assertThrows(IllegalStateException.class,()->ToolCatalog.validate(copy(value)));
            target.put("description","x".repeat(512));assertDoesNotThrow(()->ToolCatalog.validate(copy(value)));
        }
    }
    @Test void descriptionsDoNotEnableOtherSchemaFeaturesOrRemoveArrayBounds() {
        for(String field:List.of("date","items")) {
            ObjectNode value=json.valueToTree(definition());((ObjectNode)fields(value).get(field)).put("default","implicit");
            assertThrows(IllegalStateException.class,()->ToolCatalog.validate(copy(value)));
        }
        ObjectNode missing=json.valueToTree(definition());((ObjectNode)fields(missing).get("items")).remove("maxItems");
        assertThrows(IllegalStateException.class,()->ToolCatalog.validate(copy(missing)));
        ObjectNode tooMany=json.valueToTree(definition());((ObjectNode)fields(tooMany).get("items")).put("maxItems",11);
        assertThrows(IllegalStateException.class,()->ToolCatalog.validate(copy(tooMany)));
    }
    @Test void everyBuiltInParameterAndNestedItemFieldHasADescription() {
        for(var tool:new ToolCatalog().all().values())checkFields(json.valueToTree(tool.inputSchema()).path("properties"));
    }
    void checkFields(JsonNode properties) {
        for(JsonNode field:properties) {
            assertTrue(field.path("description").isTextual());assertFalse(field.path("description").asText().isBlank());
            assertTrue(field.path("description").asText().length()<=512);
            if(field.path("type").asText().equals("array"))checkFields(field.path("items").path("properties"));
        }
    }
}
