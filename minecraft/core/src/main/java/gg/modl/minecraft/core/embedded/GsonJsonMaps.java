package gg.modl.minecraft.core.embedded;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import gg.modl.update.JsonMaps;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class GsonJsonMaps implements JsonMaps {
    static final GsonJsonMaps INSTANCE = new GsonJsonMaps();

    private static final Type MAP = new TypeToken<Map<String, Object>>() { }.getType();

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private GsonJsonMaps() {
    }

    @Override
    public Map<String, Object> read(byte[] json) throws IOException {
        try {
            return gson.fromJson(new String(json, StandardCharsets.UTF_8), MAP);
        } catch (JsonParseException invalid) {
            throw new IOException("not valid JSON (" + invalid.getMessage() + ")", invalid);
        }
    }

    @Override
    public byte[] write(Map<String, Object> value) {
        return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
    }
}
