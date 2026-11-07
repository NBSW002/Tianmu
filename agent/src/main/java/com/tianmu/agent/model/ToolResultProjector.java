package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Only the disposable model view changes. Durable evidence and native call/result IDs stay intact. */
final class ToolResultProjector {
    private final ObjectMapper json;
    ToolResultProjector(ObjectMapper json){this.json=json;}
    int compact(ArrayNode results,boolean canReadBack){
        if(!canReadBack)return 0;int count=0;
        for(int i=0;i<results.size()-4;i++){
            if(!(results.get(i) instanceof ObjectNode result) || !result.path("evidenceId").isTextual() || result.path("evidenceId").asText().isBlank()
                    || !result.path("pending").isNull() && !result.path("pending").isMissingNode()
                    || !result.path("terminalAnswer").isNull() && !result.path("terminalAnswer").isMissingNode()
                    || !result.path("terminalClarification").isNull() && !result.path("terminalClarification").isMissingNode()
                    || result.path("tool").asText().equals("read_result") || result.path("data").has("error_code") || result.path("data").has("input_validation"))continue;
            try{if(json.writeValueAsBytes(result.path("data")).length<=4096)continue;}catch(java.io.IOException e){continue;}
            var compact=json.createObjectNode();compact.put("projection","EVIDENCE_REFERENCE_V1");compact.put("complete",false);
            compact.put("details","Full result remains in owned evidence; read_result returns authorized pages.");
            compact.set("readback",json.valueToTree(Map.of("tool","read_result","arguments",Map.of("result_id",result.path("evidenceId").asText(),"offset",0,"limit",2048))));
            // Preserve direct bounded scalar facts, never manufacture a preview from cut JSON.
            var facts=compact.putObject("facts");var fields=result.path("data").fields();int kept=0;
            while(fields.hasNext() && kept<12){var field=fields.next();if(field.getKey().length()<=64 && (field.getValue().isNumber() || field.getValue().isBoolean() || field.getValue().isTextual() && field.getValue().asText().length()<=120)){facts.set(field.getKey(),field.getValue());kept++;}}
            result.set("data",compact);count++;
        }
        return count;
    }
}
