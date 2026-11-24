package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.connection.BackendEndpointResolver;
import gg.modl.minecraft.core.connection.EmbeddedSettings;
import gg.modl.minecraft.core.util.Hex;
import gg.modl.minecraft.core.util.JavaRuntime;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.EmbeddedUpdateContract;
import lombok.Builder;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Builder
public final class EmbeddedServerHost {
    private static final String ENTRYPOINT_CLASS = "gg.modl.backend.embedded.EmbeddedServer";
    private static final int REQUIRED_JAVA_VERSION = EmbeddedUpdateContract.MIN_JAVA;
    private static final long RECOMMENDED_MAX_HEAP_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024;
    private static final int INTERNAL_KEY_BYTES = 32;
    private static final long PORT_RELEASE_WAIT_MILLIS = 10_000;

    private final EmbeddedSettings settings;
    private final EmbeddedServerPaths paths;
    private final ManagedPayloads payloads;
    @Builder.Default
    private final String internalApiKey = newInternalApiKey();
    @Builder.Default
    private final Map<String, Object> updateInputs = Collections.emptyMap();
    @Builder.Default
    private final Map<String, String> forwardedSwitches = Collections.emptyMap();
    private final StartupProgress progress;
    @Builder.Default
    private final Function<String, String> environment = System::getenv;
    private final String configPath;
    private final PluginLogger logger;

    public CompletableFuture<EmbeddedServerHandle> start() {
        CompletableFuture<EmbeddedServerHandle> started = new CompletableFuture<>();
        Thread launcher = new Thread(() -> launch(started), "modl-embedded-server");
        launcher.setDaemon(false);
        launcher.start();
        return started;
    }

    private void launch(CompletableFuture<EmbeddedServerHandle> started) {
        EmbeddedServerHandle handle;
        try {
            handle = startServer(started);
        } catch (Throwable failure) {
            if (!started.isCancelled()) started.completeExceptionally(asStartupFailure(failure));
            return;
        }
        if (started.isCancelled()) {
            handle.close();
            return;
        }
        new PanelPortNotice(settings, configPath, environment).announce(logger);
        if (!started.complete(handle)) handle.close();
    }

    private EmbeddedServerHandle startServer(CompletableFuture<EmbeddedServerHandle> started) throws Exception {
        progress.beginStart();
        requireSupportedJava();
        warnOnSmallHeap();
        EmbeddedServerLock lock = EmbeddedServerLock.acquire(paths.getLockFile(), started::isCancelled, logger);
        try {
            progress.advance();
            PortProbe.awaitAvailable(settings.bind(), configPath, PORT_RELEASE_WAIT_MILLIS);
            return startSelected(payloads.select(started::isCancelled), lock, started);
        } catch (Throwable failure) {
            lock.close();
            throw failure;
        }
    }

    private EmbeddedServerHandle startSelected(SelectedPayload selected, EmbeddedServerLock lock,
                                               CompletableFuture<EmbeddedServerHandle> started) throws Exception {
        if (!selected.attemptsUpdate()) return startPayload(selected, lock, started);
        EmbeddedServerHandle updated;
        try {
            updated = startPayload(selected, lock, started);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception | LinkageError failure) {
            return startRolledBack(selected, failure, lock, started);
        }
        finishUpdate(selected);
        return updated;
    }

    private EmbeddedServerHandle startRolledBack(SelectedPayload failed, Throwable failure, EmbeddedServerLock lock,
                                                 CompletableFuture<EmbeddedServerHandle> started) throws Exception {
        requireStillWanted(started);
        Optional<SelectedPayload> previous = payloads.rollBack(failed, describe(failure), started::isCancelled);
        if (!previous.isPresent()) {
            throw new EmbeddedServerException(failed.describe() + " did not start (" + describe(failure)
                    + ") and the update cannot be rolled back automatically. Restart the Minecraft server to finish or roll back"
                    + " the update. Details are in " + paths.serverLog() + ".", failure);
        }
        logger.warning(failed.describe() + " did not start (" + describe(failure) + "). Returning to "
                + previous.get().describe() + ".");
        PortProbe.awaitAvailable(settings.bind(), configPath, PORT_RELEASE_WAIT_MILLIS);
        return startPayload(previous.get(), lock, started);
    }

    private EmbeddedServerHandle startPayload(SelectedPayload selected, EmbeddedServerLock lock,
                                              CompletableFuture<EmbeddedServerHandle> started) throws Exception {
        InstalledPayload installed;
        try {
            installed = install(selected, started);
        } finally {
            selected.close();
        }
        requireStillWanted(started);
        progress.advance();
        BackendEndpoint endpoint = BackendEndpointResolver.embedded(settings, internalApiKey);
        EmbeddedServerHandle handle = EmbeddedServerHandle.start(installed.getJars(), lock, endpoint, logger,
                serverClassLoader -> entrypoint(serverClassLoader).apply(input()));
        payloads.started(installed);
        return handle;
    }

    private void finishUpdate(SelectedPayload updated) {
        try {
            if (payloads.committed(updated)) updated.payload().pruneLibrariesExcept(payloads.runningLibraries());
        } catch (IOException unfinished) {
            logger.debug("Could not tidy up after the update to " + updated.describe() + ": " + unfinished.getMessage());
        }
    }

    private static void requireStillWanted(CompletableFuture<EmbeddedServerHandle> started) {
        if (started.isCancelled()) throw new CancellationException("The built-in panel start was cancelled");
    }

    private void requireSupportedJava() {
        if (JavaRuntime.currentFeatureVersion() < REQUIRED_JAVA_VERSION) {
            throw new EmbeddedServerException("The built-in panel needs Java 21 or newer. Update Java or use mode: remote.");
        }
    }

    private void warnOnSmallHeap() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        if (maxHeap >= RECOMMENDED_MAX_HEAP_BYTES) return;
        logger.warning("This server has a maximum heap of " + maxHeap / BYTES_PER_MEBIBYTE
                + " MiB. The built-in panel shares it, so give the server at least 2 GiB (for example -Xmx2G).");
    }

    private InstalledPayload install(SelectedPayload selected, CompletableFuture<EmbeddedServerHandle> started)
            throws IOException {
        DownloadProgress downloads = new DownloadProgress(logger, progress);
        InstalledPayload installed = selected.payload().install(downloads, started::isCancelled, selected.retainedLibraries());
        if (installed.changedAnything()) {
            String downloaded = installed.getDownloadedCount() > 0 ? " (" + downloads.summary() + ")" : "";
            logger.info("Installed modl server " + installed.getVersion() + " libraries to " + paths.getLibraryDirectory()
                    + downloaded);
        }
        return installed;
    }

    @SuppressWarnings("unchecked")
    private static Function<Map<String, Object>, AutoCloseable> entrypoint(ClassLoader classLoader)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(ENTRYPOINT_CLASS, true, classLoader);
        Object instance = type.getConstructor().newInstance();
        if (!(instance instanceof Function)) {
            throw new EmbeddedServerException(ENTRYPOINT_CLASS + " is not a valid embedded entrypoint.");
        }
        return (Function<Map<String, Object>, AutoCloseable>) instance;
    }

    private Map<String, Object> input() {
        Map<String, Object> input = new HashMap<>(updateInputs);
        input.put("properties", EmbeddedServerProperties.of(settings, paths, internalApiKey, forwardedSwitches));
        input.put("logSink", new EmbeddedLogSink(logger));
        input.put("logDir", paths.getLogDirectory().toString());
        return input;
    }

    public static String newInternalApiKey() {
        byte[] key = new byte[INTERNAL_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return Hex.encode(key);
    }

    private EmbeddedServerException asStartupFailure(Throwable failure) {
        if (failure instanceof EmbeddedServerException) return (EmbeddedServerException) failure;
        return new EmbeddedServerException("The built-in panel failed to start: " + describe(failure)
                + ". Details are in " + paths.serverLog() + ".", failure);
    }

    private static String describe(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }
}
