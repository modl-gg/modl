package gg.modl.minecraft.core.config;

import gg.modl.minecraft.core.config.yaml.ManagedConfig;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class GuiSections {
    private GuiSections() {
    }

    static @Nullable Map<?, ?> configured(Path dataDirectory, ManagedConfig file, String sectionName) throws IOException {
        Map<?, ?> dedicated = read(dataDirectory.resolve(file.getFileName()));
        if (dedicated != null) return sectionOrWhole(dedicated, sectionName);
        Map<?, ?> config = read(dataDirectory.resolve("config.yml"));
        Object section = config == null ? null : config.get(sectionName);
        return section instanceof Map ? (Map<?, ?>) section : null;
    }

    static Map<?, ?> packaged(ManagedConfig file, String sectionName) {
        String text = file.readPackagedDefault();
        if (text == null) throw new IllegalStateException("The plugin jar does not contain its " + file.getFileName());
        Object parsed = new Yaml().load(text);
        if (!(parsed instanceof Map)) throw new IllegalStateException("The plugin jar's " + file.getFileName() + " is empty");
        return sectionOrWhole((Map<?, ?>) parsed, sectionName);
    }

    private static Map<?, ?> sectionOrWhole(Map<?, ?> document, String sectionName) {
        Object section = document.get(sectionName);
        return section instanceof Map ? (Map<?, ?>) section : document;
    }

    private static @Nullable Map<?, ?> read(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        try (InputStream stream = Files.newInputStream(file)) {
            Object parsed = new Yaml().load(stream);
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        }
    }
}
