package gg.modl.minecraft.core;

import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.FakePlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModlStatusCommandTest {
    @TempDir
    Path dataDirectory;

    private final FakePlatform platform = new FakePlatform();
    private PluginLoader loader;

    @BeforeEach
    void loadCommands() {
        loader = PluginLoader.create(platform, dataDirectory, new ChatMessageCache(), () -> CoreState.STARTING, new LoadedDriverLibraries(), false, false);
    }

    @AfterEach
    void stop() {
        loader.shutdown();
    }

    @Test
    void theConsoleGetsAReportEvenWhileModlIsNotConnected() {
        FakeCommandActor console = new FakeCommandActor(null, "CONSOLE");
        platform.addConsoleSender(console);

        platform.lamp().dispatch(console, "modl status");

        String replies = String.join("\n", console.messages());
        assertTrue(replies.contains(message("support_status.header")), replies);
        assertTrue(replies.contains("modl plugin status"), replies);
        assertTrue(replies.contains("State: starting"), replies);
        assertTrue(replies.contains("Panel URL: not known yet"), replies);
        assertTrue(replies.contains(message("support_status.backend_unavailable")), replies);
        assertTrue(replies.contains(message("support_status.footer")), replies);
        assertFalse(replies.contains(message("api_errors.connection_failed")), replies);
    }

    @Test
    void aPlayerIsRefusedInEveryState() {
        FakeCommandActor player = new FakeCommandActor(UUID.randomUUID(), "Player");

        platform.lamp().dispatch(player, "modl status");

        String replies = String.join("\n", player.messages()) + String.join("\n", player.errors());
        assertTrue(replies.contains(message("general.local_console_only")), replies);
        assertFalse(replies.contains("modl plugin status"), replies);
    }

    private String message(String key) {
        return loader.getLocaleManager().getMessage(key);
    }
}
