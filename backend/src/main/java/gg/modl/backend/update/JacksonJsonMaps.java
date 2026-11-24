package gg.modl.backend.update;

import gg.modl.update.JsonMaps;
import java.io.IOException;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class JacksonJsonMaps implements JsonMaps {
    public static final JacksonJsonMaps INSTANCE = new JacksonJsonMaps();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JacksonJsonMaps() {
    }

    @Override
    public Map<String, Object> read(byte[] json) throws IOException {
        try {
            return MAPPER.readValue(json, MAP);
        } catch (JacksonException e) {
            throw new IOException("Invalid JSON", e);
        }
    }

    @Override
    public byte[] write(Map<String, Object> value) throws IOException {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JacksonException e) {
            throw new IOException("The value could not be written as JSON", e);
        }
    }
}
