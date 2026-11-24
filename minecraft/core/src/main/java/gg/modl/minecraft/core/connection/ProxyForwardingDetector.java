package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.util.YamlValues;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ProxyForwardingDetector {
    static final String SPIGOT_CONFIG = "spigot.yml";
    static final String PAPER_GLOBAL_CONFIG = "config/paper-global.yml";
    static final String LEGACY_PAPER_CONFIG = "paper.yml";

    private final Path serverRoot;

    public ProxyForwardingDetector(Path serverRoot) {
        this.serverRoot = serverRoot;
    }

    public ProxyForwarding detect() {
        if (flag(PAPER_GLOBAL_CONFIG, "proxies.velocity.enabled")
                || flag(LEGACY_PAPER_CONFIG, "settings.velocity-support.enabled")) {
            return ProxyForwarding.VELOCITY;
        }
        if (flag(SPIGOT_CONFIG, "settings.bungeecord")) return ProxyForwarding.BUNGEECORD;
        return ProxyForwarding.NONE;
    }

    private boolean flag(String relativePath, String key) {
        Path file = serverRoot.resolve(relativePath);
        if (!Files.isRegularFile(file)) return false;
        try (InputStream input = Files.newInputStream(file)) {
            Object root = new Yaml().load(input);
            return root instanceof Map && YamlValues.coerceBoolean(YamlValues.nested((Map<?, ?>) root, key), false);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}
