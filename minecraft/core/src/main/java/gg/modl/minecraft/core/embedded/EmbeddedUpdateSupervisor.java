package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.HostDescriptor;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVersion;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

public final class EmbeddedUpdateSupervisor {
    static final String THREAD_NAME = "modl-embedded-update";

    private final ManagedPayloads payloads;
    private final ReleaseVersion pluginVersion;
    private final ReleaseKeys keys;
    private final StartupProgress progress;
    private final Runnable swap;
    private final PluginLogger logger;
    private final ThreadPoolExecutor executor;
    private volatile boolean stopped;

    public EmbeddedUpdateSupervisor(ManagedPayloads payloads, ReleaseVersion pluginVersion, ReleaseKeys keys,
                                    StartupProgress progress, Runnable swap, PluginLogger logger) {
        this.payloads = payloads;
        this.pluginVersion = pluginVersion;
        this.keys = keys;
        this.progress = progress;
        this.swap = swap;
        this.logger = logger;
        this.executor = hostThread(EmbeddedUpdateSupervisor.class.getClassLoader());
    }

    public Map<String, Object> contractInputs() {
        Function<String, CompletionStage<Void>> prepare = this::prepareAsync;
        Runnable restart = this::requestRestart;
        Consumer<String> phase = progress::reportPhase;
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(EmbeddedUpdateContract.PREPARE, prepare);
        inputs.put(EmbeddedUpdateContract.RESTART, restart);
        inputs.put(EmbeddedUpdateContract.PHASE, phase);
        inputs.put(EmbeddedUpdateContract.HOST,
                new HostDescriptor(EmbeddedUpdateContract.HOST_API, pluginVersion, keys.keyIds()).toString());
        return Collections.unmodifiableMap(inputs);
    }

    public void shutdown() {
        stopped = true;
        executor.shutdownNow();
    }

    void prepare(String versionText) throws IOException {
        ReleaseVersion target = ReleaseVersion.tryParse(versionText);
        if (target == null || !target.sameLine(pluginVersion) || !target.isNewerThan(pluginVersion)) {
            throw new IOException("modl " + versionText + " is not a newer patch release of modl " + pluginVersion.line()
                    + ". Replace the modl plugin jar instead.");
        }
        InstalledPayload installed;
        try (VerifiedArchive archive = payloads.verifiedArchive(target)) {
            installed = archive.payload().install(new DownloadProgress(logger, new StartupProgress()), () -> stopped,
                    payloads.runningLibraries());
        }
        logger.info("Prepared modl " + installed.getVersion() + ". The built-in panel restarts to finish the update.");
    }

    private CompletionStage<Void> prepareAsync(String version) {
        CompletableFuture<Void> prepared = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    prepare(version);
                    prepared.complete(null);
                } catch (IOException | RuntimeException | LinkageError failure) {
                    prepared.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException stopping) {
            prepared.completeExceptionally(new IOException("The plugin is stopping", stopping));
        }
        return prepared;
    }

    private void requestRestart() {
        try {
            executor.execute(this::restart);
        } catch (RejectedExecutionException stopping) {
            logger.debug("The update restart was not started because the plugin is stopping: " + stopping);
        }
    }

    private void restart() {
        if (stopped) return;
        try {
            if (!payloads.hasStagedUpdate()) {
                logger.warning("The built-in panel asked to restart for an update, but no staged update was found.");
                return;
            }
        } catch (IOException unreadable) {
            logger.warning("The staged update could not be read: " + unreadable.getMessage()
                    + ". Restart the Minecraft server to finish or roll back the update.");
            return;
        }
        swap.run();
    }

    private static ThreadPoolExecutor hostThread(ClassLoader pluginLoader) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_NAME);
                    thread.setDaemon(true);
                    thread.setContextClassLoader(pluginLoader);
                    return thread;
                });
        executor.prestartAllCoreThreads();
        return executor;
    }
}
