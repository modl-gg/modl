package gg.modl.minecraft.core.impl.http.proto;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ProtoConversions {

    private ProtoConversions() {
    }

    public static Map<String, Object> structToMap(Struct struct) {
        Map<String, Object> result = new LinkedHashMap<>();
        struct.getFieldsMap().forEach((key, value) -> {
            Object converted = valueToObject(value);
            if (converted != null) result.put(key, converted);
        });
        return result;
    }

    public static Struct mapToStruct(Map<String, Object> map) {
        Struct.Builder builder = Struct.newBuilder();
        map.forEach((key, value) -> builder.putFields(key, objectToValue(value)));
        return builder.build();
    }

    private static @Nullable Object valueToObject(Value value) {
        switch (value.getKindCase()) {
            case NUMBER_VALUE:
                return value.getNumberValue();
            case STRING_VALUE:
                return value.getStringValue();
            case BOOL_VALUE:
                return value.getBoolValue();
            case STRUCT_VALUE:
                return structToMap(value.getStructValue());
            case LIST_VALUE:
                List<@Nullable Object> list = new ArrayList<>();
                value.getListValue().getValuesList().forEach(item -> list.add(valueToObject(item)));
                return list;
            case NULL_VALUE:
            case KIND_NOT_SET:
            default:
                return null;
        }
    }

    private static Value objectToValue(@Nullable Object object) {
        Value.Builder builder = Value.newBuilder();
        if (object == null) {
            return builder.setNullValue(NullValue.NULL_VALUE).build();
        }
        if (object instanceof String) {
            return builder.setStringValue((String) object).build();
        }
        if (object instanceof Number) {
            return builder.setNumberValue(((Number) object).doubleValue()).build();
        }
        if (object instanceof Boolean) {
            return builder.setBoolValue((Boolean) object).build();
        }
        if (object instanceof Map<?, ?>) {
            Struct.Builder struct = Struct.newBuilder();
            ((Map<?, ?>) object).forEach((key, value) -> struct.putFields(Objects.toString(key), objectToValue(value)));
            return builder.setStructValue(struct).build();
        }
        if (object instanceof Iterable<?>) {
            ListValue.Builder list = ListValue.newBuilder();
            ((Iterable<?>) object).forEach(item -> list.addValues(objectToValue(item)));
            return builder.setListValue(list).build();
        }
        return builder.setStringValue(Objects.toString(object)).build();
    }

    public static @Nullable Instant instantFromMillis(long millis) {
        return millis > 0 ? Instant.ofEpochMilli(millis) : null;
    }

    public static @Nullable String emptyToNull(@Nullable String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    public static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
