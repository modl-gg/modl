package gg.modl.backend.database;

import gg.modl.backend.infrastructure.json.InstantJsonFormat;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class JsonColumns {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    public JsonColumns() {
        this.mapper = JsonMapper.builder(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNameLength(Integer.MAX_VALUE)
                    .maxStringLength(Integer.MAX_VALUE)
                    .build())
                .build())
            .addModule(InstantJsonFormat.module())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public @Nullable String writeIfPresent(@Nullable Object value) {
        return value == null ? null : write(value);
    }

    @Nullable
    public <T> T read(@Nullable String json, Class<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    @Nullable
    public <T> T read(@Nullable String json, TypeReference<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    public Map<String, Object> readMap(@Nullable String json) {
        if (json == null) {
            return Map.of();
        }
        return mapper.readValue(json, MAP);
    }
}
