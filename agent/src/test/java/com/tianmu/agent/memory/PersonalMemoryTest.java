package com.tianmu.agent.memory;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.api.PersonalMemoryController.Change;
import com.tianmu.agent.memory.PersonalMemory.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class PersonalMemoryTest {
    @Test void rejectsUnknownFieldsAtEveryLevelEvenWithBootStyleMapper() {
        var json=new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String valid="{\"expectedVersion\":0,\"content\":{\"scope\":{\"type\":\"USER\",\"key\":\"self\"},\"kind\":\"PREFERENCE\",\"text\":\"简短回答\"}}";
        assertDoesNotThrow(()->json.readValue(valid,Change.class));
        for(String invalid:new String[]{valid.replace("\"expectedVersion\":0","\"sessionId\":\"fake\",\"expectedVersion\":0"),
                valid.replace("\"kind\":","\"source\":\"SYSTEM\",\"kind\":"),
                valid.replace("\"key\":\"self\"","\"key\":\"self\",\"owner\":\"bob\"")})
            assertThrows(Exception.class,()->json.readValue(invalid,Change.class));
    }
    @Test void validatesUnicodeByteBoundsAndDatabaseDates() {
        assertThrows(RuntimeException.class,()->new Content(Scope.user(),Kind.PREFERENCE,"中".repeat(342),null));
        assertThrows(RuntimeException.class,()->new Content(Scope.user(),Kind.PREFERENCE,"\uD800",null));
        assertThrows(RuntimeException.class,()->new Content(Scope.user(),Kind.PREFERENCE,"valid",Instant.MAX));
        assertThrows(RuntimeException.class,()->new Scope(ScopeType.VIDEO,"9223372036854775808"));
        assertThrows(RuntimeException.class,()->new Scope(ScopeType.USER,"bob"));
        var expiry=Instant.parse("2027-01-01T00:00:00.123456789Z");
        assertEquals(Instant.parse("2027-01-01T00:00:00.123456Z"),new Content(Scope.user(),Kind.GOAL,"valid",expiry).expiresAt());
    }
    @Test void indexesChineseAndNormalizesLatinWithoutUnboundedTerms() {
        var terms=MemoryKeywords.terms("以后简短回答，RUNNING 入门",1024);
        assertTrue(terms.contains("简短"));assertTrue(terms.contains("入门"));assertTrue(terms.contains("running"));
        assertEquals(terms.size(),terms.stream().distinct().count());
        assertTrue(MemoryKeywords.terms("很多中文".repeat(100),16).size()<=16);
    }
}
