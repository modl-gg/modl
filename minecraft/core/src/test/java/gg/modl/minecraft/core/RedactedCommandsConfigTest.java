package gg.modl.minecraft.core;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedactedCommandsConfigTest {
    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void aMissingKeyAddsNothing() {
        assertEquals(Collections.emptyList(), PluginConfiguration.loadRedactedCommands(Collections.emptyMap(), logger));
    }

    @Test
    void listEntriesAreReadAsText() {
        assertEquals(Arrays.asList("pin", "2468"), load(Arrays.asList("pin", null, 2468)));
        assertEquals(Collections.emptyList(), logger.warnings());
    }

    @Test
    void aSingleCommandIsAccepted() {
        assertEquals(Collections.singletonList("pin"), load("pin"));
    }

    @Test
    void anotherShapeWarnsAndAddsNothing() {
        assertEquals(Collections.emptyList(), load(Collections.singletonMap("pin", true)));
        assertEquals(1, logger.warnings().size());
    }

    private List<String> load(Object configured) {
        return PluginConfiguration.loadRedactedCommands(Collections.singletonMap("redacted_commands", configured), logger);
    }
}
