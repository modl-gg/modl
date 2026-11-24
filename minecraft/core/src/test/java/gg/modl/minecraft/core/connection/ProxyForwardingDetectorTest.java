package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.support.TempConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyForwardingDetectorTest {
    @TempDir
    Path serverRoot;

    @Test
    void reportsNoForwardingWithoutServerConfigs() {
        assertEquals(ProxyForwarding.NONE, detect());
    }

    @Test
    void detectsBungeeCordForwardingFromSpigotYml() throws IOException {
        TempConfigs.write(serverRoot.resolve("spigot.yml"), "settings:", "  bungeecord: true");

        assertEquals(ProxyForwarding.BUNGEECORD, detect());
    }

    @Test
    void ignoresDisabledBungeeCordForwarding() throws IOException {
        TempConfigs.write(serverRoot.resolve("spigot.yml"), "settings:", "  bungeecord: false");

        assertEquals(ProxyForwarding.NONE, detect());
    }

    @Test
    void detectsVelocityForwardingFromPaperGlobalConfig() throws IOException {
        Files.createDirectories(serverRoot.resolve("config"));
        TempConfigs.write(serverRoot.resolve("config/paper-global.yml"),
                "proxies:", "  velocity:", "    enabled: true", "    online-mode: true", "    secret: 'abc'");
        TempConfigs.write(serverRoot.resolve("spigot.yml"), "settings:", "  bungeecord: false");

        assertEquals(ProxyForwarding.VELOCITY, detect());
    }

    @Test
    void detectsVelocityForwardingFromLegacyPaperYml() throws IOException {
        TempConfigs.write(serverRoot.resolve("paper.yml"), "settings:", "  velocity-support:", "    enabled: true");

        assertEquals(ProxyForwarding.VELOCITY, detect());
    }

    @Test
    void treatsUnreadableConfigsAsDisabled() throws IOException {
        TempConfigs.write(serverRoot.resolve("spigot.yml"), "settings: [unclosed");

        assertEquals(ProxyForwarding.NONE, detect());
    }

    @Test
    void detectsBungeeCordForwardingFromSpigot188ConfigFiles() throws IOException {
        TempConfigs.write(serverRoot.resolve("spigot.yml"),
                "config-version: 8",
                "settings:",
                "  save-user-cache-on-stop-only: false",
                "  bungeecord: true",
                "  late-bind: false",
                "  sample-count: 12",
                "  timeout-time: 60",
                "  restart-on-crash: true",
                "  restart-script: ./start.sh",
                "  netty-threads: 4",
                "  attribute:",
                "    maxHealth:",
                "      max: 2048.0",
                "  debug: false",
                "commands:",
                "  tab-complete: 0",
                "  log: true",
                "  spam-exclusions:",
                "  - /skill",
                "world-settings:",
                "  default:",
                "    verbose: true");
        TempConfigs.write(serverRoot.resolve("paper.yml"),
                "config-version: 9",
                "settings:",
                "  baby-zombie-movement-speed: 0.5",
                "  limit-player-interactions: true",
                "world-settings:",
                "  default:",
                "    verbose: true");

        assertEquals(ProxyForwarding.BUNGEECORD, detect());
    }

    private ProxyForwarding detect() {
        return new ProxyForwardingDetector(serverRoot).detect();
    }
}
