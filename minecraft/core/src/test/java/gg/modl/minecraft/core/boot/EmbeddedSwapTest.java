package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.embedded.EmbeddedServerException;
import gg.modl.minecraft.core.embedded.EmbeddedServerHandle;
import gg.modl.minecraft.core.embedded.EmbeddedServerPaths;
import gg.modl.minecraft.core.embedded.StartupProgress;
import gg.modl.minecraft.core.embedded.TestEmbeddedHandles;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedSwapTest {
    private static final BackendEndpoint ENDPOINT = BackendEndpoint.of("http://127.0.0.1:8080", "internal-key");

    @TempDir
    Path folder;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final AtomicInteger launches = new AtomicInteger();
    private final AtomicBoolean firstClosed = new AtomicBoolean();
    private final CompletableFuture<EmbeddedServerHandle> replacement = new CompletableFuture<>();
    private EmbeddedBackendSource source;

    @BeforeEach
    void startPanel() throws Exception {
        CompletableFuture<EmbeddedServerHandle> first = CompletableFuture.completedFuture(
                TestEmbeddedHandles.running(folder.resolve("first"), ENDPOINT, logger, () -> firstClosed.set(true)));
        source = new EmbeddedBackendSource(EmbeddedServerPaths.forPluginDataFolder(folder),
                logger, new StartupProgress(), progress -> launches.getAndIncrement() == 0 ? first : replacement);
        source.start();
        assertEquals(ENDPOINT, source.endpoint().get(10, TimeUnit.SECONDS));
    }

    @AfterEach
    void stopPanel() {
        source.shutdown();
    }

    @Test
    void theOldPanelStopsBeforeTheNewOneStartsAndTheEndpointStays() throws Exception {
        source.swap();

        assertTrue(firstClosed.get());
        assertEquals(2, launches.get());
        replacement.complete(TestEmbeddedHandles.running(folder.resolve("second"), ENDPOINT, logger, () -> { }));

        assertEquals(ENDPOINT, source.endpoint().join());
        assertTrue(logger.infos().contains("The built-in panel restarted. Players can join again."), logger.infos().toString());
        assertTrue(logger.severes().isEmpty(), logger.severes().toString());
    }

    @Test
    void aFailedReplacementSaysPlayersCannotJoinAndHowToRecover() {
        source.swap();

        replacement.completeExceptionally(new EmbeddedServerException("boom"));

        assertTrue(logger.severes().stream().anyMatch(line -> line.contains("boom")), logger.severes().toString());
        assertTrue(logger.severes().contains("Restart the Minecraft server to finish or roll back the update."),
                logger.severes().toString());
    }

    @Test
    void shuttingDownDuringASwapCancelsTheReplacement() {
        source.swap();

        source.shutdown();

        assertTrue(replacement.isCancelled());
        assertTrue(logger.severes().isEmpty(), logger.severes().toString());
        assertFalse(logger.infos().contains("The built-in panel restarted. Players can join again."));
    }

    @Test
    void aDatabaseMoveRestartsThePanelOffTheCallingThread() throws Exception {
        source.restartForDatabase();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (launches.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(firstClosed.get());
        assertEquals(2, launches.get());
        assertTrue(logger.infos().contains("Restarting the built-in panel to finish moving the database..."), logger.infos().toString());
        replacement.completeExceptionally(new EmbeddedServerException("unreachable"));

        assertTrue(logger.severes().contains("Restart the Minecraft server to finish moving the database."),
                logger.severes().toString());
    }

    @Test
    void aStoppedSourceNeverSwaps() {
        source.shutdown();

        source.swap();

        assertEquals(1, launches.get());
    }
}
