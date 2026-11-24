package gg.modl.backend.settings.service;

import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

public final class SettingsCodec<T> {
    private static final Logger log = LoggerFactory.getLogger(SettingsCodec.class);
    private static final JsonMapper DOCUMENT_MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();
    private static final JavaType DOCUMENT_TYPE =
        DOCUMENT_MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

    private final JavaType type;
    private final Supplier<T> defaults;

    private SettingsCodec(JavaType type, Supplier<T> defaults) {
        this.type = type;
        this.defaults = defaults;
    }

    public static <T> SettingsCodec<T> of(Class<T> type, Supplier<T> defaults) {
        return new SettingsCodec<>(DOCUMENT_MAPPER.getTypeFactory().constructType(type), defaults);
    }

    public static <T> SettingsCodec<T> of(TypeReference<T> type, Supplier<T> defaults) {
        return new SettingsCodec<>(DOCUMENT_MAPPER.getTypeFactory().constructType(type), defaults);
    }

    public static Map<String, Object> toDocument(Object value) {
        return DOCUMENT_MAPPER.convertValue(value, DOCUMENT_TYPE);
    }

    public T decode(@Nullable Object data) {
        if (data == null || (data instanceof Map<?, ?> map && map.isEmpty())) {
            return defaults.get();
        }
        try {
            return DOCUMENT_MAPPER.convertValue(data, type);
        } catch (JacksonException exception) {
            log.warn("Failed to decode settings of type {}, using defaults: {}", type, exception.getMessage());
            return defaults.get();
        }
    }

    public T decodeJson(String json) {
        try {
            return decode(DOCUMENT_MAPPER.readValue(json, DOCUMENT_TYPE));
        } catch (JacksonException exception) {
            log.warn("Failed to read settings of type {}, using defaults: {}", type, exception.getMessage());
            return defaults.get();
        }
    }

    public T decodeStrict(@Nullable Object data) {
        if (data == null) {
            throw new IllegalStateException("The stored " + type + " settings document is null");
        }
        return DOCUMENT_MAPPER.convertValue(data, type);
    }

    public Map<String, Object> encode(T value) {
        return toDocument(value);
    }
}
