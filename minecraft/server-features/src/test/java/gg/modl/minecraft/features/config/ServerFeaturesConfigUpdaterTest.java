package gg.modl.minecraft.features.config;

import gg.modl.minecraft.core.config.yaml.ConfigUpdate;
import gg.modl.minecraft.core.config.yaml.ConfigUpdater;
import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerFeaturesConfigUpdaterTest {

    private static final PluginLogger LOGGER = new PluginLogger() {
        @Override public void info(String message) {}
        @Override public void warning(String message) {}
        @Override public void severe(String message) {}
    };

    @TempDir
    Path dataFolder;

    @Test
    void addsMissingKeysToAnOlderServerFeaturesConfigAndKeepsTheOwnersValues() throws IOException {
        write("server-features.yml", "proxy-host: \"10.0.0.5\"\nreport-cooldown: 30\n");

        ConfigUpdate update = ConfigUpdater.update(ServerFeaturesManagedConfigs.SERVER_FEATURES, dataFolder, LOGGER);

        assertTrue(update.getAddedPaths().contains("server-name"));
        Map<?, ?> loaded = load("server-features.yml");
        assertEquals("10.0.0.5", loaded.get("proxy-host"));
        assertEquals(30, loaded.get("report-cooldown"));
    }

    @Test
    void keepsCustomViolationThresholdsIntact() throws IOException {
        write("server-features.yml", "report-violation-threshold:\n  badpackets: 25\n");

        ConfigUpdater.update(ServerFeaturesManagedConfigs.SERVER_FEATURES, dataFolder, LOGGER);

        Map<?, ?> thresholds = (Map<?, ?>) load("server-features.yml").get("report-violation-threshold");
        assertEquals(1, thresholds.size());
        assertEquals(25, thresholds.get("badpackets"));
        assertFalse(thresholds.containsKey("default"));
    }

    @Test
    void doesNotResurrectHotbarSlotsTheOwnerRemoved() throws IOException {
        write("staff_mode.yml", "staff_hotbar:\n  0:\n    item: \"minecraft:stick\"\n");

        ConfigUpdater.update(ServerFeaturesManagedConfigs.STAFF_MODE, dataFolder, LOGGER);

        Map<?, ?> hotbar = (Map<?, ?>) load("staff_mode.yml").get("staff_hotbar");
        assertEquals(1, hotbar.size());
    }

    @Test
    void doesNotRestoreScoreboardLinesTheOwnerTrimmed() throws IOException {
        write("staff_mode.yml", "staff_scoreboard:\n  enabled: true\n  lines:\n    - \"just one\"\n");

        ConfigUpdater.update(ServerFeaturesManagedConfigs.STAFF_MODE, dataFolder, LOGGER);

        Map<?, ?> scoreboard = (Map<?, ?>) load("staff_mode.yml").get("staff_scoreboard");
        assertEquals(1, ((java.util.List<?>) scoreboard.get("lines")).size());
        assertTrue(scoreboard.containsKey("title"));
    }

    @Test
    void shippedDefaultsAreStableAcrossRepeatedRuns() {
        ConfigUpdater.update(ServerFeaturesManagedConfigs.SERVER_FEATURES, dataFolder, LOGGER);
        ConfigUpdater.update(ServerFeaturesManagedConfigs.STAFF_MODE, dataFolder, LOGGER);

        assertFalse(ConfigUpdater.update(ServerFeaturesManagedConfigs.SERVER_FEATURES, dataFolder, LOGGER).isChanged());
        assertFalse(ConfigUpdater.update(ServerFeaturesManagedConfigs.STAFF_MODE, dataFolder, LOGGER).isChanged());
    }

    @Test
    void anUpdatedFileStillLoadsIntoServerFeaturesConfig() throws IOException {
        write("server-features.yml", "server-name: \"Hub\"\n");

        ConfigUpdater.update(ServerFeaturesManagedConfigs.SERVER_FEATURES, dataFolder, LOGGER);

        ServerFeaturesConfig config = ServerFeaturesConfig.load(dataFolder);
        assertEquals("Hub", config.getServerName());
        assertEquals(60, config.getReportCooldown());
    }

    private void write(String name, String content) throws IOException {
        Files.write(dataFolder.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private Map<?, ?> load(String name) throws IOException {
        return new Yaml().load(new String(Files.readAllBytes(dataFolder.resolve(name)), StandardCharsets.UTF_8));
    }
}
