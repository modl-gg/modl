package gg.modl.minecraft.core;

import gg.modl.minecraft.core.config.PackagedConfig;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigurationDefaultsTest {
    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void commandAliasFallbacksAreTheShippedConfigYml() {
        Map<String, String> aliases = PluginConfiguration.loadCommandAliases(Collections.emptyMap(), logger);

        Map<String, Object> shipped = PackagedConfig.section("commands");
        assertEquals(shipped.keySet(), aliases.keySet());
        for (Map.Entry<String, Object> entry : shipped.entrySet()) {
            assertEquals(String.valueOf(entry.getValue()), aliases.get(entry.getKey()), entry.getKey());
        }
        assertEquals("replay", aliases.get("replay"));
        assertEquals("staffmode|modmode|sm|mm", aliases.get("staffmode"));
        assertEquals("staffmenu", aliases.get("staffmenu"));
    }

    @Test
    void aConfiguredAliasReplacesOnlyItsOwnFallback() {
        Map<String, Object> config = new HashMap<>();
        config.put("commands", Collections.singletonMap("ban", "ban|b"));

        Map<String, String> aliases = PluginConfiguration.loadCommandAliases(config, logger);

        assertEquals("ban|b", aliases.get("ban"));
        assertEquals("punish|p", aliases.get("punish"));
    }

    @Test
    void mutedCommandsFallBackToTheShippedListWithTeamMessages() {
        List<String> muted = PluginConfiguration.loadMutedCommands(Collections.emptyMap(), logger);

        assertTrue(muted.containsAll(Arrays.asList("msg", "tell", "w", "me", "teammsg", "tm")));
        assertEquals(Collections.emptyList(),
                PluginConfiguration.loadMutedCommands(Collections.singletonMap("muted_commands", Collections.emptyList()), logger));
    }

    @Test
    void chatAndCommandLoggingAreOnByDefault() {
        PluginConfiguration.LoggingConfig logging = PluginConfiguration.loadLoggingConfig(Collections.emptyMap(), logger);

        assertTrue(logging.chat);
        assertTrue(logging.commands);
        assertEquals(Collections.emptyList(), logging.neverLoggedCommands);
    }

    @Test
    void eachLoggingSwitchIsReadOnItsOwn() {
        Map<String, Object> logging = new HashMap<>();
        logging.put("chat", false);
        logging.put("never_logged_commands", Arrays.asList("msg", "r"));

        PluginConfiguration.LoggingConfig loaded = PluginConfiguration.loadLoggingConfig(
                Collections.singletonMap("logging", logging), logger);

        assertFalse(loaded.chat);
        assertTrue(loaded.commands);
        assertEquals(Arrays.asList("msg", "r"), loaded.neverLoggedCommands);
        assertEquals(Collections.emptyList(), logger.warnings());
    }
}
