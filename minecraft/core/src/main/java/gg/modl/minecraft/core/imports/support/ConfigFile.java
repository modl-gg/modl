package gg.modl.minecraft.core.imports.support;

import gg.modl.minecraft.core.imports.source.SourceException;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigFile {
    private static final @Nullable Constructor<SafeConstructor> PLAIN_SAFE_CONSTRUCTOR = plainSafeConstructor();

    private final Map<?, ?> root;

    private ConfigFile(Map<?, ?> root) {
        this.root = root;
    }

    public static ConfigFile read(Path file) throws SourceException {
        if (!Files.isRegularFile(file)) return new ConfigFile(Collections.emptyMap());
        try (InputStream input = Files.newInputStream(file)) {
            Object loaded = safeYaml().load(input);
            return new ConfigFile(loaded instanceof Map ? (Map<?, ?>) loaded : Collections.emptyMap());
        } catch (MarkedYAMLException unreadable) {
            Mark mark = unreadable.getProblemMark();
            String where = mark == null ? "" : " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
            throw SourceException.failed("Could not read " + file + where);
        } catch (IOException | YAMLException unreadable) {
            throw SourceException.failed("Could not read " + file);
        }
    }

    private static Yaml safeYaml() {
        Constructor<SafeConstructor> plain = PLAIN_SAFE_CONSTRUCTOR;
        if (plain == null) return new Yaml();
        try {
            return new Yaml(plain.newInstance());
        } catch (ReflectiveOperationException unavailable) {
            throw new YAMLException("The YAML reader could not be created: " + unavailable);
        }
    }

    private static @Nullable Constructor<SafeConstructor> plainSafeConstructor() {
        try {
            return SafeConstructor.class.getConstructor();
        } catch (NoSuchMethodException tagsRefusedByDefault) {
            return null;
        }
    }

    public @Nullable Object value(String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<?, ?>) current).get(part);
        }
        return current;
    }

    public String string(String path, String fallback) {
        Object value = value(path);
        return value == null ? fallback : String.valueOf(value);
    }

    public int integer(String path, int fallback) {
        Object value = value(path);
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException notNumber) {
            return fallback;
        }
    }

    public boolean bool(String path, boolean fallback) {
        Object value = value(path);
        if (value instanceof Boolean) return (Boolean) value;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    public Map<String, String> strings(String path) {
        Object value = value(path);
        Map<String, String> entries = new LinkedHashMap<>();
        if (value instanceof Map) {
            ((Map<?, ?>) value).forEach((key, entry) -> {
                if (key != null && entry != null) entries.put(String.valueOf(key), String.valueOf(entry));
            });
        }
        return entries;
    }
}
