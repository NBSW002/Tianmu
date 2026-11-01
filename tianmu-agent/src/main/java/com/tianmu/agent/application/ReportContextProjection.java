package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tianmu.agent.core.AgentTypes.ToolCall;
import java.util.*;

/** Deterministic report-only presentation policy. Receipts come from admitted host tool results. */
public final class ReportContextProjection {
    public record Plan(List<ToolCall> calls, int inputFieldsReleased, int batchesReleased, int bytesSaved) {
        public Plan { calls = List.copyOf(calls); }
    }
    private record Receipt(int index, String evidenceId, int submitted, int accepted, String callId) {}
    private final ObjectMapper json;
    public ReportContextProjection(ObjectMapper json) { this.json = json; }

    /** results must be a disposable copy, and calls must already be paired to the execution ledger. */
    public Plan apply(List<ToolCall> calls, ArrayNode results) {
        if (calls.size() != results.size()) throw new IllegalArgumentException("Unpaired report projection");
        List<Receipt> receipts = new ArrayList<>();
        Map<String, Receipt> latest = new HashMap<>();
        for (int i=0; i<calls.size(); i++) {
            ToolCall call=calls.get(i); JsonNode result=results.get(i), data=result.path("data");
            String evidence=data.path("evidence_id").asText("");
            Integer submitted=integer(json.valueToTree(call.arguments().get("offset")));
            Integer accepted=integer(data.path("classification").path("offset"));
            Integer total=integer(data.path("classification").path("total_groups"));
            if (!call.name().equals("create_danmaku_report") || !result.path("tool").asText().equals(call.name())
                    || data.has("error_code") || evidence.isEmpty() || !evidence.equals(result.path("evidenceId").asText())
                    || !evidence.equals(call.arguments().get("evidence_id")) || submitted==null || accepted==null || total==null
                    || !(call.arguments().get("topics_json") instanceof String topics) || topics.isBlank()
                    || submitted<0 || accepted<=submitted || accepted>total || total>5000) continue;
            var receipt=new Receipt(i,evidence,submitted,accepted,call.providerCallId()); receipts.add(receipt);
            var old=latest.get(evidence); if (old==null || old.accepted()<=accepted) latest.put(evidence,receipt);
        }
        List<ToolCall> projected=new ArrayList<>(calls);int inputs=0,batches=0,saved=0;
        for (Receipt receipt : receipts) {
            ToolCall call=calls.get(receipt.index());
            if (!(call.arguments().get("topics_json") instanceof String)) continue;
            ObjectNode original=(ObjectNode)results.get(receipt.index()), candidate=original.deepCopy();
            var arguments=new LinkedHashMap<>(call.arguments());arguments.remove("topics_json");
            var metadata=((ObjectNode)candidate.get("data")).putObject("context_projection");
            metadata.put("reason","classification_accepted");metadata.putArray("input_fields_omitted").add("topics_json");
            metadata.put("submitted_offset",receipt.submitted());metadata.put("accepted_offset",receipt.accepted());
            metadata.put("evidence_id",receipt.evidenceId());
            metadata.put("note","Historical argument omitted from this view; do not replay projected arguments. Original feedback is available through read_result.");
            int gain=bytes(call.arguments(),original)-bytes(arguments,candidate);
            if (gain<=0) continue;
            projected.set(receipt.index(),new ToolCall(call.name(),arguments,call.providerCallId(),call.providerGroupId()));
            results.set(receipt.index(),candidate);inputs++;saved+=gain;
        }
        for (int i=0; i<results.size(); i++) {
            ObjectNode original=(ObjectNode)results.get(i);JsonNode data=original.path("data");
            String evidence=data.path("evidence_id").asText("");var receipt=latest.get(evidence);
            if (receipt==null || receipt.index()<=i || !evidence.equals(original.path("evidenceId").asText())
                    || !Set.of("query_audience","query_danmaku","create_danmaku_report").contains(original.path("tool").asText())
                    || data.has("error_code")) continue;
            JsonNode classification=data.path("classification");Integer offset=integer(classification.path("offset"));
            if (offset==null || offset<0 || offset>=receipt.accepted() || !classification.path("groups").isArray()) continue;
            long end=offset;boolean valid=true;
            for (JsonNode group : classification.path("groups")) {
                Integer records=group.has("original_records")?integer(group.get("original_records")):1;
                if (records==null || records<1) { valid=false;break; } end+=records;
            }
            // A compressed group may cover many original records. Do not drop its unread remainder.
            if (!valid || end>receipt.accepted()) continue;
            ObjectNode candidate=original.deepCopy();var body=(ObjectNode)candidate.path("data").path("classification");
            body.remove(List.of("groups","topics"));body.put("superseded",true);
            body.put("accepted_through",receipt.accepted());body.put("superseded_by_call_id",receipt.callId());
            int gain=bytes(projected.get(i).arguments(),original)-bytes(projected.get(i).arguments(),candidate);
            if (gain<=0) continue;
            results.set(i,candidate);batches++;saved+=gain;
        }
        return new Plan(projected,inputs,batches,saved);
    }
    private Integer integer(JsonNode node) { return node!=null && node.isIntegralNumber() && node.canConvertToInt()?node.intValue():null; }
    private int bytes(Map<String,Object> arguments, JsonNode result) {
        try { return json.writeValueAsBytes(Map.of("arguments",json.writeValueAsString(arguments),"content",json.writeValueAsString(result))).length; }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Invalid projection payload",e); }
    }
}
