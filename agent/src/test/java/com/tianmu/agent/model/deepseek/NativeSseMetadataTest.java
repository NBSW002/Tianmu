package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSseMetadataTest {
    @Test void preservesProviderModelNameWithoutTreatingTheRequestedAliasAsResolved(){
        var parser=new NativeSseResponse(new ObjectMapper(),ignored->{});
        parser.accept("{\"model\":\"provider-version-20261108\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}");parser.accept("[DONE]");assertEquals("provider-version-20261108",parser.result().path("model").asText());
        var missing=new NativeSseResponse(new ObjectMapper(),ignored->{});missing.accept("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");missing.accept("[DONE]");assertFalse(missing.result().has("model"));
    }
}
