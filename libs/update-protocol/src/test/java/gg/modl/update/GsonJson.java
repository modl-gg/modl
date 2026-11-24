package gg.modl.update;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class GsonJson implements JsonMaps {
    private static final Type MAP = new TypeToken<Map<String, Object>>() { }.getType();
    private final Gson gson = new Gson();

    @Override
    public Map<String, Object> read(byte[] json) {
        return gson.fromJson(new String(json, StandardCharsets.UTF_8), MAP);
    }

    @Override
    public byte[] write(Map<String, Object> value) {
        return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
    }
}
