package gg.modl.backend.infrastructure.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtoMapperSupportInstantTest {
    private static final Instant AT = Instant.parse("2026-01-01T12:34:56.789Z");
    private static final Instant BEFORE_EPOCH = Instant.ofEpochMilli(-1);
    private static final Instant MAX_SENTINEL = Instant.parse("9999-12-31T23:59:59.999Z");

    @Test
    void anInstantSetsItsEpochMillis() {
        List<Long> set = new ArrayList<>();

        ProtoMapperSupport.setOptionalEpochMillis(set::add, AT);

        assertEquals(List.of(AT.toEpochMilli()), set);
    }

    @Test
    void aNullInstantSetsNoEpochMillis() {
        List<Long> set = new ArrayList<>();

        ProtoMapperSupport.setOptionalEpochMillis(set::add, null);

        assertTrue(set.isEmpty());
    }

    @Test
    void anInstantSetsItsTimestamp() {
        List<Timestamp> fromInstant = new ArrayList<>();

        ProtoMapperSupport.setOptionalTimestamp(fromInstant::add, AT);

        assertEquals(Timestamp.newBuilder().setSeconds(AT.getEpochSecond()).setNanos(789_000_000).build(), fromInstant.getFirst());
    }

    @Test
    void anInstantPastTheTimestampRangeClampsToTheMaxSentinel() {
        List<Timestamp> set = new ArrayList<>();

        ProtoMapperSupport.setOptionalTimestamp(set::add, Instant.MAX);

        assertEquals(Timestamp.newBuilder().setSeconds(MAX_SENTINEL.getEpochSecond()).setNanos(999_000_000).build(), set.getFirst());
    }

    @Test
    void anInstantPastTheTimestampRangeSetsTheMaxSentinelMillis() {
        List<Long> set = new ArrayList<>();

        ProtoMapperSupport.setOptionalEpochMillis(set::add, Instant.MAX);

        assertEquals(List.of(MAX_SENTINEL.toEpochMilli()), set);
    }

    @Test
    void aNullInstantSetsNoTimestamp() {
        List<Timestamp> set = new ArrayList<>();

        ProtoMapperSupport.setOptionalTimestamp(set::add, null);

        assertTrue(set.isEmpty());
    }

    @Test
    void anInstantBeforeTheEpochFloorsToTheEarlierSecond() {
        assertEquals(Timestamp.newBuilder().setSeconds(-1).setNanos(999_000_000).build(), ProtoMapperSupport.toTimestamp(BEFORE_EPOCH));
    }

    @Test
    void anInstantIsItsEpochMillisAsALong() {
        assertEquals(AT.toEpochMilli(), ProtoMapperSupport.longValue(AT));
    }

    @Test
    void aCanonicalStructCarriesAnInstantAsEpochMillis() {
        Value instant = canonicalValue(AT);

        assertEquals(Value.newBuilder().setNumberValue((double) AT.toEpochMilli()).build(), instant);
    }

    @Test
    void aLegacyStructCarriesAnInstantAsIsoText() {
        Value instant = legacyValue(AT);

        assertEquals(Value.newBuilder().setStringValue("2026-01-01T12:34:56.789Z").build(), instant);
    }

    private static Value canonicalValue(Object value) {
        Struct struct = ProtoMapperSupport.toStruct(Map.of("at", value), ProtoMapperSupport.StructEncoding.CANONICAL);
        return struct.getFieldsOrThrow("at");
    }

    private static Value legacyValue(Object value) {
        return ProtoMapperSupport.legacyStruct(Map.of("at", value)).getFieldsOrThrow("at");
    }
}
