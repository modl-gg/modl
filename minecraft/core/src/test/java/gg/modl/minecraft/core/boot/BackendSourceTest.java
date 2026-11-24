package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.connection.BackendEndpointResolver;
import gg.modl.minecraft.core.connection.RemoteSettings;
import gg.modl.minecraft.core.embedded.EmbeddedServerException;
import gg.modl.minecraft.core.embedded.EmbeddedServerHandle;
import gg.modl.minecraft.core.embedded.EmbeddedServerPaths;
import gg.modl.minecraft.core.embedded.StartupProgress;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendSourceTest {
    @TempDir
    Path dataFolder;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void anEmbeddedStartFailureSurfacesItsMessage() {
        BackendSource source = embedded(failedStart("Port 8080 is already in use."));
        source.start();

        assertEquals("Port 8080 is already in use.", failureOf(source.endpoint()).getMessage());
    }

    @Test
    void theEndpointIsPendingWhileTheEmbeddedServerStartsAndNeverBlocksTheCaller() {
        CompletableFuture<EmbeddedServerHandle> launch = new CompletableFuture<>();
        BackendSource source = embedded(launch);
        source.start();

        CompletableFuture<BackendEndpoint> endpoint = source.endpoint();

        assertFalse(endpoint.isDone());
        assertSame(endpoint, source.endpoint());
        source.shutdown();
        assertTrue(launch.isCancelled());
        assertTrue(endpoint.isCompletedExceptionally());
    }

    @Test
    void aStalledStartIsAbandonedAndReportsTheStall() throws Exception {
        CompletableFuture<EmbeddedServerHandle> launch = new CompletableFuture<>();
        AtomicLong now = new AtomicLong();
        StartupProgress progress = new StartupProgress(() -> now.addAndGet(TimeUnit.SECONDS.toNanos(10)));
        BackendSource source = new EmbeddedBackendSource(logger, progress,
                new StartupWatch(progress, logger, dataFolder.resolve("server.log"), 1), started -> launch);
        source.start();

        ExecutionException stalled = assertThrows(ExecutionException.class, () -> source.endpoint().get(10, TimeUnit.SECONDS));

        assertTrue(stalled.getCause().getMessage().contains("no startup progress for 180 seconds"), stalled.getCause().getMessage());
        assertTrue(launch.isCancelled());
    }

    @Test
    void aStalledStartRecordsTheStallSoTheNextStartRollsBack() {
        CompletableFuture<EmbeddedServerHandle> launch = new CompletableFuture<>();
        AtomicLong now = new AtomicLong();
        StartupProgress progress = new StartupProgress(() -> now.addAndGet(TimeUnit.SECONDS.toNanos(10)));
        List<String> recorded = new CopyOnWriteArrayList<>();
        BackendSource source = new EmbeddedBackendSource(logger, progress,
                new StartupWatch(progress, logger, dataFolder.resolve("server.log"), 1), started -> launch, recorded::add);
        source.start();

        assertThrows(ExecutionException.class, () -> source.endpoint().get(10, TimeUnit.SECONDS));

        assertEquals(1, recorded.size());
        assertTrue(recorded.get(0).contains("no startup progress"), recorded.toString());
    }

    @Test
    void aStoppedEmbeddedSourceNeverRelaunches() {
        int[] launches = {0};
        BackendSource source = new EmbeddedBackendSource(
                EmbeddedServerPaths.forPluginDataFolder(dataFolder), logger, new StartupProgress(), progress -> {
            launches[0]++;
            return failedStart("unused");
        });

        source.shutdown();
        source.start();

        assertEquals(0, launches[0]);
        assertEquals("The built-in panel is not running.", failureOf(source.endpoint()).getMessage());
    }

    @Test
    void shuttingDownCancelsALaunchThatHasNotFinished() {
        CompletableFuture<EmbeddedServerHandle> launch = new CompletableFuture<>();
        BackendSource source = embedded(launch);
        source.start();

        source.shutdown();

        assertTrue(launch.isCancelled());
    }

    @Test
    void aRemoteServerNamesTheInstanceKeyInItsRemedy() {
        RemoteSettings remote = RemoteSettings.builder().url("https://modl.example.com/modl").apiKey("modl_key").build();
        BackendSource source = new RemoteBackendSource(remote, BackendEndpointResolver.fromEnvironment(), "connection.yml", logger);

        assertNull(source.currentPanelUrl());
        assertTrue(source.rejectedCredentialsRemedy().contains("remote.api-key"));
    }

    private BackendSource embedded(CompletableFuture<EmbeddedServerHandle> start) {
        return new EmbeddedBackendSource(EmbeddedServerPaths.forPluginDataFolder(dataFolder), logger,
                new StartupProgress(), progress -> start);
    }

    private static EmbeddedServerException failureOf(CompletableFuture<BackendEndpoint> endpoint) {
        CompletionException failure = assertThrows(CompletionException.class, endpoint::join);
        return assertInstanceOf(EmbeddedServerException.class, failure.getCause());
    }

    private static CompletableFuture<EmbeddedServerHandle> failedStart(String message) {
        CompletableFuture<EmbeddedServerHandle> future = new CompletableFuture<>();
        future.completeExceptionally(new EmbeddedServerException(message));
        return future;
    }
}
