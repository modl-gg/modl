package gg.modl.minecraft.core.embedded;

import gg.modl.backend.embedded.EmbeddedServer;
import gg.modl.minecraft.core.connection.EmbeddedSettings;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.update.UpdatePhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static gg.modl.minecraft.core.embedded.EmbeddedReleases.PATCH;
import static gg.modl.minecraft.core.embedded.EmbeddedReleases.PLUGIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedUpdateStartTest {
    private static final long START_SECONDS = 30;

    @TempDir
    Path temp;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final String internalKey = EmbeddedServerHost.newInternalApiKey();
    private EmbeddedServerPaths paths;
    private EmbeddedReleases releases;
    private ManagedPayloads payloads;
    private EmbeddedSettings settings;

    @BeforeEach
    void publishPatch() throws Exception {
        paths = EmbeddedServerPaths.forPluginDataFolder(temp.resolve("modl"));
        releases = new EmbeddedReleases(temp.resolve("work"), paths, logger);
        releases.publish(PATCH);
        payloads = releases.payloads();
        try (ServerSocket socket = new ServerSocket(0)) {
            settings = EmbeddedSettings.builder().port(socket.getLocalPort()).bindAddress("127.0.0.1").build();
        }
    }

    @AfterEach
    void clearFakeServerState() {
        System.clearProperty(EmbeddedServer.CLOSED_PROPERTY);
        System.clearProperty(EmbeddedServer.INTERNAL_KEY_PROPERTY);
        System.clearProperty(EmbeddedServer.STARTING_PROPERTY);
        System.clearProperty(EmbeddedServer.FAIL_UPDATE_PROPERTY);
    }

    @Test
    void aPatchReplacesTheRunningPanelOnTheSamePortAndKeyAndPrunesAfterTheCommit() throws Exception {
        EmbeddedServerHandle before = start();
        String baseUrl = before.endpoint().baseUrl();
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());
        assertTrue(before.close());

        EmbeddedServerHandle after = start();

        assertEquals(baseUrl, after.endpoint().baseUrl());
        assertEquals(internalKey, after.endpoint().getApiKey());
        assertNull(releases.files().readPending());
        assertEquals(PATCH, releases.files().readCommitted().version());
        assertTrue(installed(EmbeddedReleases.patchLibrary(PATCH)));
        assertFalse(installed(EmbeddedReleases.patchLibrary(PLUGIN)));
        after.close();
    }

    @Test
    void aFailedPatchReturnsToThePackedPayload() throws Exception {
        System.setProperty(EmbeddedServer.FAIL_UPDATE_PROPERTY, "true");
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());

        EmbeddedServerHandle restored = start();

        assertNull(releases.files().readPending());
        assertNull(releases.files().readCommitted());
        assertTrue(installed(EmbeddedReleases.patchLibrary(PLUGIN)));
        assertFalse(installed(EmbeddedReleases.patchLibrary(PATCH)));
        assertTrue(logger.warnings().stream().anyMatch(line -> line.contains("Returning to modl 3.0.0")));
        restored.close();
    }

    @Test
    void aFailedMigrationWithoutABackupFailsClosedAndKeepsThePendingUpdate() throws Exception {
        System.setProperty(EmbeddedServer.FAIL_UPDATE_PROPERTY, "true");
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).phase(UpdatePhase.MIGRATING).schemaChanged(true).build());

        ExecutionException failure = assertThrows(ExecutionException.class, this::start);

        assertTrue(failure.getCause().getMessage().contains("Restart the Minecraft server"), failure.getCause().getMessage());
        assertEquals(UpdatePhase.MIGRATING, releases.files().readPending().phase());
    }

    private boolean installed(String library) throws IOException {
        return Files.exists(paths.getLibraryDirectory().resolve(library));
    }

    private EmbeddedServerHandle start() throws Exception {
        return EmbeddedServerHost.builder()
                .settings(settings)
                .paths(paths)
                .payloads(payloads)
                .internalApiKey(internalKey)
                .progress(new StartupProgress())
                .configPath("plugins/modl/connection.yml")
                .logger(logger)
                .build()
                .start()
                .get(START_SECONDS, TimeUnit.SECONDS);
    }
}
