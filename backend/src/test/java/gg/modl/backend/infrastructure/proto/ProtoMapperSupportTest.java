package gg.modl.backend.infrastructure.proto;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtoMapperSupportTest {

    @Test
    void addressesAreDisplayedCompressedAndAMissingAddressIsEmpty() {
        assertThat(ProtoMapperSupport.addressValue("2001:db8:0:0:0:0:0:1")).isEqualTo("2001:db8::1");
        assertThat(ProtoMapperSupport.addressValue("198.51.100.4")).isEqualTo("198.51.100.4");
        assertThat(ProtoMapperSupport.addressValue(null)).isEmpty();
    }

    @Test
    void binarySerializesToBase64NotDebugString() {
        byte[] bytes = {1, 2, 3, 4};
        Struct struct = ProtoMapperSupport.toStruct(Map.of("blob", bytes));
        String value = struct.getFieldsMap().get("blob").getStringValue();
        assertThat(value).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(value).doesNotContain("[B@");
    }

    @Test
    void uuidRoundTripsToCanonicalString() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Struct struct = ProtoMapperSupport.toStruct(Map.of("uuid", uuid));
        assertThat(struct.getFieldsMap().get("uuid").getStringValue()).isEqualTo(uuid.toString());
    }

    @Test
    void scalarsAndNestedStructuresAreUnchanged() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("str", "hello");
        input.put("lng", 42L);
        input.put("dbl", 3.5);
        input.put("bool", true);
        input.put("date", Instant.ofEpochMilli(1000L));
        input.put("nested", Map.of("inner", "x"));
        input.put("list", List.of("a", "b"));

        Struct struct = ProtoMapperSupport.toStruct(input);
        Map<String, Value> fields = struct.getFieldsMap();

        assertThat(fields.get("str").getStringValue()).isEqualTo("hello");
        assertThat(fields.get("lng").getNumberValue()).isEqualTo(42.0);
        assertThat(fields.get("dbl").getNumberValue()).isEqualTo(3.5);
        assertThat(fields.get("bool").getBoolValue()).isTrue();
        assertThat(fields.get("date").getNumberValue()).isEqualTo(1000.0);
        assertThat(fields.get("nested").getStructValue().getFieldsMap().get("inner").getStringValue()).isEqualTo("x");
        assertThat(fields.get("list").getListValue().getValuesCount()).isEqualTo(2);
    }

    @Test
    void unexpectedTypeDegradesToStringWithoutThrowing() {
        Object pojo = new Object() {
            @Override
            public String toString() {
                return "pojo-value";
            }
        };
        Struct struct = ProtoMapperSupport.toStruct(Map.of("weird", pojo));
        assertThat(struct.getFieldsMap().get("weird").getStringValue()).isEqualTo("pojo-value");
    }
}
