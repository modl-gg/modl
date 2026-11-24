package gg.modl.minecraft.core.config;

import gg.modl.minecraft.core.config.yaml.CoreManagedConfigs;
import org.yaml.snakeyaml.Yaml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PackagedConfig {
    private PackagedConfig() {
    }

    public static Map<String, Object> root() {
        return Holder.ROOT;
    }

    public static Map<String, Object> section(String name) {
        Object section = root().get(name);
        if (!(section instanceof Map)) return Collections.emptyMap();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) section).entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static final class Holder {
        private static final Map<String, Object> ROOT = load();

        private static Map<String, Object> load() {
            String text = CoreManagedConfigs.CONFIG.readPackagedDefault();
            if (text == null) throw new IllegalStateException("The plugin jar does not contain its config.yml");
            Map<String, Object> parsed = new Yaml().load(text);
            if (parsed == null) throw new IllegalStateException("The plugin jar's config.yml is empty");
            return Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        }
    }
}
