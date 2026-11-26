package gg.modl.minecraft.features.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ServerFeaturesConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void replayAndAnticheatSettingsLiveInThePanelNotInTheFile() throws IOException {
        Map<String, Object> defaults = loadClasspathServerFeaturesConfig();

        for (String key : defaults.keySet()) {
            assertFalse(key.startsWith("replay-") || key.startsWith("anticheat-"), key + " belongs to the panel settings");
        }
    }

    @Test
    void reloadReportsRestartRequiredSettingsWithoutApplyingThem() {
        ServerFeaturesConfig current = ServerFeaturesConfig.builder().build();
        ServerFeaturesConfig reloaded = ServerFeaturesConfig.builder()
                .serverName("Hub")
                .build();

        assertEquals(Arrays.asList("server-name"), current.settingsNeedingRestart(reloaded));

        current.applyReloadableSettings(reloaded);

        assertEquals("", current.getServerName());
    }

    @Test
    void everySavedSettingIsEitherAppliedOnReloadOrReportedAsRestartRequired() throws IOException {
        ServerFeaturesConfig current = ServerFeaturesConfig.builder().build();
        Map<String, Object> defaults = savedYaml(current, "defaults");

        ServerFeaturesConfig reloaded = fullyChangedConfig();
        Map<String, Object> changed = savedYaml(reloaded, "changed");

        List<String> restartRequired = current.settingsNeedingRestart(reloaded);
        current.applyReloadableSettings(reloaded);
        Map<String, Object> applied = savedYaml(current, "applied");

        for (String key : changed.keySet()) {
            assertNotEquals(defaults.get(key), changed.get(key),
                    key + " must differ from its default for this test to cover it");
            if (restartRequired.contains(key)) {
                assertEquals(defaults.get(key), applied.get(key), key + " is restart-required but was applied live");
            } else {
                assertEquals(changed.get(key), applied.get(key), key + " is neither applied nor restart-required");
            }
        }
    }

    private Map<String, Object> savedYaml(ServerFeaturesConfig config, String directoryName) throws IOException {
        Path dir = tempDir.resolve(directoryName);
        config.save(dir);
        return loadYaml(dir.resolve("server-features.yml"));
    }

    private static ServerFeaturesConfig fullyChangedConfig() {
        Map<String, Integer> thresholds = new LinkedHashMap<>();
        thresholds.put("default", 3);
        return ServerFeaturesConfig.builder()
                .statWipeCommands(Arrays.asList("wipe {player}"))
                .serverName("Hub")
                .reportCooldown(5)
                .reportViolationThresholds(thresholds)
                .debug(true)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadClasspathServerFeaturesConfig() throws IOException {
        try (InputStream input = ServerFeaturesConfigTest.class.getResourceAsStream("/server-features.yml")) {
            assertNotNull(input);
            return (Map<String, Object>) new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return (Map<String, Object>) new Yaml().load(input);
        }
    }
}
