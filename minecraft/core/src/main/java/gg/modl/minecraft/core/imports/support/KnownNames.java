package gg.modl.minecraft.core.imports.support;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class KnownNames {
    private static final String USER_CACHE = "usercache.json";

    private final Map<String, String> uuidsByLowerName = new HashMap<>();
    private final Map<String, String> namesByUuid = new HashMap<>();

    public static KnownNames fromServer(Path serverRoot) {
        KnownNames names = new KnownNames();
        Path file = serverRoot.resolve(USER_CACHE);
        if (!Files.isRegularFile(file)) return names;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonArray()) names.addAll(root.getAsJsonArray());
        } catch (IOException | RuntimeException unreadable) {
            return names;
        }
        return names;
    }

    private void addAll(JsonArray entries) {
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject()) continue;
            JsonObject object = entry.getAsJsonObject();
            String uuid = PlayerKeys.uuidOrNull(text(object, "uuid"));
            String name = text(object, "name");
            if (uuid != null && name != null) remember(uuid, name);
        }
    }

    public void remember(String uuid, String name) {
        uuidsByLowerName.put(name.toLowerCase(Locale.ROOT), uuid);
        namesByUuid.put(uuid, name);
    }

    public @Nullable String uuidOf(@Nullable String name) {
        return name == null ? null : uuidsByLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    public @Nullable String nameOf(@Nullable String uuid) {
        return uuid == null ? null : namesByUuid.get(uuid);
    }

    private static @Nullable String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
