package gg.modl.minecraft.core.imports.support;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class LatestValues {
    private final Map<String, Value> latestByKey = new HashMap<>();

    public void seen(@Nullable String key, @Nullable String value, long at) {
        if (key == null || value == null) return;
        Value current = latestByKey.get(key);
        if (current == null || current.at < at) latestByKey.put(key, new Value(value, at));
    }

    public @Nullable String latest(@Nullable String key) {
        Value value = key == null ? null : latestByKey.get(key);
        return value == null ? null : value.text;
    }

    private static final class Value {
        private final String text;
        private final long at;

        private Value(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }
}
