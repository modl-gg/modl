package gg.modl.backend.infrastructure.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InstantJsonFormatTest {
    private final JsonMapper mapper = customizedMapper();

    @Test
    void anInstantWithZeroMillisecondsKeepsThreeFractionDigits() {
        assertEquals("\"1970-01-01T00:00:20.000Z\"", mapper.writeValueAsString(Instant.ofEpochSecond(20)));
    }

    @Test
    void anInstantKeepsItsMilliseconds() {
        assertEquals("\"2026-01-01T12:34:56.789Z\"", mapper.writeValueAsString(Instant.parse("2026-01-01T12:34:56.789Z")));
    }

    @Test
    void subMillisecondPrecisionIsTruncated() {
        assertEquals("\"2026-01-01T00:00:00.123Z\"", mapper.writeValueAsString(Instant.parse("2026-01-01T00:00:00.123987Z")));
    }

    @Test
    void anInstantNestedInAValueUsesTheSameFormat() {
        assertEquals("{\"at\":\"1970-01-01T00:00:20.000Z\"}", mapper.writeValueAsString(Map.of("at", Instant.ofEpochSecond(20))));
    }

    private static JsonMapper customizedMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new InstantJsonConfiguration().instantJsonFormat().customize(builder);
        return builder.build();
    }
}
