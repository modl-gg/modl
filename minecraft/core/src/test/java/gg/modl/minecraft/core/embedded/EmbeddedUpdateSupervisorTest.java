package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.HostDescriptor;
import gg.modl.update.UpdatePhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static gg.modl.minecraft.core.embedded.EmbeddedReleases.MINOR;
import static gg.modl.minecraft.core.embedded.EmbeddedReleases.PATCH;
import static gg.modl.minecraft.core.embedded.EmbeddedReleases.PLUGIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedUpdateSupervisorTest {
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path temp;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final StartupProgress progress = new StartupProgress();
    private final CountDownLatch swapped = new CountDownLatch(1);
    private final AtomicReference<Thread> swappedOn = new AtomicReference<>();
    private EmbeddedServerPaths paths;
    private EmbeddedReleases releases;
    private EmbeddedUpdateSupervisor supervisor;
    private Map<String, Object> inputs;

    @BeforeEach
    void startSupervisor() throws Exception {
        paths = EmbeddedServerPaths.forPluginDataFolder(temp.resolve("modl"));
        releases = new EmbeddedReleases(temp.resolve("work"), paths, logger);
        ManagedPayloads payloads = releases.payloads();
        SelectedPayload running = payloads.select(() -> false);
        payloads.started(running.payload().install(new DownloadProgress(logger, progress), () -> false));
        supervisor = new EmbeddedUpdateSupervisor(payloads, PLUGIN, releases.keys(), progress, () -> {
            swappedOn.set(Thread.currentThread());
            swapped.countDown();
        }, logger);
        inputs = supervisor.contractInputs();
    }

    @AfterEach
    void stopSupervisor() {
        supervisor.shutdown();
    }

    @Test
    void theHostDescribesTheContractThePluginVersionAndItsKeys() {
        HostDescriptor host = HostDescriptor.parse((String) inputs.get(EmbeddedUpdateContract.HOST));

        assertEquals(EmbeddedUpdateContract.HOST_API, host.api());
        assertEquals(PLUGIN, host.version());
        assertEquals(releases.keys().sortedKeyIds(), host.keyIds());
    }

    @Test
    void preparingAPatchInstallsItsLibrariesBesideTheRunningOnes() throws Exception {
        releases.publish(PATCH);

        prepare(PATCH.toString()).toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertTrue(Files.exists(paths.getLibraryDirectory().resolve(EmbeddedReleases.patchLibrary(PATCH))));
        assertTrue(Files.exists(paths.getLibraryDirectory().resolve(EmbeddedReleases.patchLibrary(PLUGIN))));
    }

    @Test
    void anotherLineIsRefused() throws Exception {
        releases.publish(MINOR);

        CompletionException refused = assertThrows(CompletionException.class,
                () -> prepare(MINOR.toString()).toCompletableFuture().join());

        assertTrue(refused.getCause().getMessage().contains("Replace the modl plugin jar"), refused.getCause().getMessage());
    }

    @Test
    void aReleaseThatIsNotNewerThanThePluginIsRefused() {
        assertThrows(IOException.class, () -> supervisor.prepare(PLUGIN.toString()));
        assertThrows(IOException.class, () -> supervisor.prepare("../../3.0.1"));
    }

    @Test
    void aRestartRunsOnTheHostsOwnThread() throws Exception {
        releases.files().writePending(EmbeddedReleases.update(PLUGIN, PATCH).build());

        ((Runnable) inputs.get(EmbeddedUpdateContract.RESTART)).run();

        assertTrue(swapped.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(EmbeddedUpdateSupervisor.THREAD_NAME, swappedOn.get().getName());
        assertEquals(EmbeddedUpdateSupervisor.class.getClassLoader(), swappedOn.get().getContextClassLoader());
    }

    @Test
    void aRestartWithoutAStagedUpdateNeverStopsThePanel() throws Exception {
        ((Runnable) inputs.get(EmbeddedUpdateContract.RESTART)).run();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (logger.warnings().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);

        assertEquals(1, swapped.getCount());
        assertTrue(logger.warnings().get(0).contains("no staged update"), logger.warnings().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportedPhasesWidenTheStallLimit() {
        Consumer<String> phase = (Consumer<String>) inputs.get(EmbeddedUpdateContract.PHASE);

        phase.accept(UpdatePhase.BACKING_UP.name());
        assertEquals(StartupProgress.BACKUP_STALL_TIMEOUT_SECONDS, progress.stallTimeoutSeconds());
        phase.accept(UpdatePhase.MIGRATING.name());
        assertEquals(StartupProgress.UPDATE_STALL_TIMEOUT_SECONDS, progress.stallTimeoutSeconds());
        progress.beginStart();
        assertEquals(StartupProgress.STALL_TIMEOUT_SECONDS, progress.stallTimeoutSeconds());
    }

    @SuppressWarnings("unchecked")
    private CompletionStage<Void> prepare(String version) {
        return ((Function<String, CompletionStage<Void>>) inputs.get(EmbeddedUpdateContract.PREPARE)).apply(version);
    }
}
