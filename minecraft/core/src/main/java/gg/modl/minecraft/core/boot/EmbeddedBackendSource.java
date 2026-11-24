package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.PluginConfiguration;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.connection.EmbeddedSettings;
import gg.modl.minecraft.core.embedded.EmbeddedServerException;
import gg.modl.minecraft.core.embedded.EmbeddedServerHandle;
import gg.modl.minecraft.core.embedded.EmbeddedServerHost;
import gg.modl.minecraft.core.embedded.EmbeddedServerPaths;
import gg.modl.minecraft.core.embedded.EmbeddedUpdateSupervisor;
import gg.modl.minecraft.core.embedded.ForwardedSwitches;
import gg.modl.minecraft.core.embedded.ManagedPayloads;
import gg.modl.minecraft.core.embedded.PayloadArchives;
import gg.modl.minecraft.core.embedded.PinnedPayload;
import gg.modl.minecraft.core.embedded.StartupProgress;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVersion;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

final class EmbeddedBackendSource implements BackendSource {
    private static final PanelRestart UPDATE_RESTART = new PanelRestart("Restarting the built-in panel to finish the update...",
            "The built-in panel was not running when the update asked it to restart.",
            "The built-in panel did not stop for the update.",
            "The built-in panel did not start again for the update",
            "Restart the Minecraft server to finish or roll back the update.");
    private static final PanelRestart DATABASE_RESTART = new PanelRestart(
            "Restarting the built-in panel to finish moving the database...",
            "The built-in panel was not running when it asked to restart onto its new database.",
            "The built-in panel did not stop to move to its new database.",
            "The built-in panel did not start again on its new database",
            "Restart the Minecraft server to finish moving the database.");

    private final PluginLogger logger;
    private final StartupProgress progress;
    private final StartupWatch watch;
    private final Function<StartupProgress, CompletableFuture<EmbeddedServerHandle>> launcher;
    private final Runnable stopUpdates;
    private final Consumer<String> stalledUpdate;
    private @Nullable CompletableFuture<EmbeddedServerHandle> server;
    private @Nullable CompletableFuture<EmbeddedServerHandle> watched;
    private @Nullable CompletableFuture<BackendEndpoint> endpoint;
    private @Nullable StartupFailureAnnouncer updateFailure;
    private boolean stopped;
    private volatile @Nullable EmbeddedServerHandle handle;

    EmbeddedBackendSource(EmbeddedSettings settings, Path dataFolder, String configPath, PluginLogger logger) {
        EmbeddedServerPaths paths = EmbeddedServerPaths.forPluginDataFolder(dataFolder);
        this.logger = logger;
        this.progress = new StartupProgress();
        this.watch = new StartupWatch(progress, logger, paths.serverLog());
        Map<String, String> forwardedSwitches = ForwardedSwitches.forwarded(PluginConfiguration.updateChecksEnabled(dataFolder, logger),
                settings.getDownloadUrl());
        ReleaseKeys keys = ReleaseKeys.bundled();
        PayloadArchives archives = PayloadArchives.forPaths(paths, logger);
        PinnedPayload pinned = PinnedPayload.forPlugin(EmbeddedBackendSource.class.getClassLoader(), paths, forwardedSwitches, keys,
                archives, progress, configPath, logger);
        ReleaseVersion pluginVersion = ReleaseVersion.tryParse(PluginInfo.VERSION);
        ManagedPayloads payloads;
        Map<String, Object> updateInputs;
        if (pluginVersion == null) {
            payloads = ManagedPayloads.unmanaged(pinned);
            updateInputs = Collections.emptyMap();
            this.stopUpdates = () -> { };
        } else {
            payloads = ManagedPayloads.managed(pinned, archives, pluginVersion, paths.getDataDirectory(), keys, logger);
            EmbeddedUpdateSupervisor updates =
                    new EmbeddedUpdateSupervisor(payloads, pluginVersion, keys, progress, this::swap, logger);
            updateInputs = updates.contractInputs();
            this.stopUpdates = updates::shutdown;
        }
        this.stalledUpdate = payloads::abandonStalledAttempt;
        Map<String, Object> hostInputs = new HashMap<>(updateInputs);
        Runnable panelRestart = this::restartForDatabase;
        hostInputs.put(EmbeddedUpdateContract.PANEL_RESTART, panelRestart);
        this.launcher = hostLauncher(settings, configPath, paths, payloads, Collections.unmodifiableMap(hostInputs), forwardedSwitches,
                logger);
    }

    EmbeddedBackendSource(EmbeddedServerPaths paths, PluginLogger logger, StartupProgress progress,
                          Function<StartupProgress, CompletableFuture<EmbeddedServerHandle>> launcher) {
        this(logger, progress, new StartupWatch(progress, logger, paths.serverLog()), launcher);
    }

    EmbeddedBackendSource(PluginLogger logger, StartupProgress progress, StartupWatch watch,
                          Function<StartupProgress, CompletableFuture<EmbeddedServerHandle>> launcher) {
        this(logger, progress, watch, launcher, reason -> { });
    }

    EmbeddedBackendSource(PluginLogger logger, StartupProgress progress, StartupWatch watch,
                          Function<StartupProgress, CompletableFuture<EmbeddedServerHandle>> launcher,
                          Consumer<String> stalledUpdate) {
        this.logger = logger;
        this.progress = progress;
        this.watch = watch;
        this.launcher = launcher;
        this.stopUpdates = () -> { };
        this.stalledUpdate = stalledUpdate;
    }

    @Override
    public synchronized void start() {
        if (server == null && !stopped) server = launcher.apply(progress);
    }

    @Override
    public synchronized CompletableFuture<BackendEndpoint> endpoint() {
        CompletableFuture<BackendEndpoint> known = endpoint;
        if (known != null) return known;
        CompletableFuture<EmbeddedServerHandle> launch = server;
        if (launch == null) return notRunning();
        CompletableFuture<EmbeddedServerHandle> watching = watch.watch(launch, stalledUpdate);
        watched = watching;
        CompletableFuture<BackendEndpoint> connecting = watching.whenComplete((started, failure) -> {
            if (failure != null) abandon(launch);
        }).thenApply(this::connected);
        endpoint = connecting;
        return connecting;
    }

    @Override
    public @Nullable String currentPanelUrl() {
        EmbeddedServerHandle current = handle;
        return current == null ? null : current.publicUrl();
    }

    @Override
    public String rejectedCredentialsRemedy() {
        return "The built-in panel rejected this server's internal key. Restart the server.";
    }

    @Override
    public synchronized void shutdown() {
        stopped = true;
        handle = null;
        stopUpdates.run();
        stopUpdateFailure();
        CompletableFuture<EmbeddedServerHandle> watching = watched;
        if (watching != null) watching.cancel(false);
        CompletableFuture<EmbeddedServerHandle> launch = server;
        if (launch != null) abandon(launch);
        server = null;
        watched = null;
        endpoint = null;
    }

    void swap() {
        restart(UPDATE_RESTART);
    }

    void restartForDatabase() {
        Thread restart = new Thread(() -> restart(DATABASE_RESTART), "modl-embedded-restart");
        restart.setDaemon(true);
        restart.setContextClassLoader(EmbeddedBackendSource.class.getClassLoader());
        restart.start();
    }

    private void restart(PanelRestart reason) {
        EmbeddedServerHandle previous;
        synchronized (this) {
            if (stopped) return;
            previous = handle;
            if (previous == null) {
                failRestart(reason.getNotRunning(), reason);
                return;
            }
            handle = null;
        }
        logger.info(reason.getStarting());
        if (previous.close()) {
            startReplacement(reason);
        } else {
            failRestart(reason.getNotStopped(), reason);
        }
    }

    private synchronized void startReplacement(PanelRestart reason) {
        if (stopped) return;
        progress.beginStart();
        CompletableFuture<EmbeddedServerHandle> replacement = launcher.apply(progress);
        server = replacement;
        CompletableFuture<EmbeddedServerHandle> watching = watch.watch(replacement, stalledUpdate);
        watched = watching;
        Futures.whenComplete(watching, (started, failure) -> {
            if (failure == null) {
                adoptReplacement(started);
            } else {
                abandon(replacement);
                failRestart(reason.getNotStarted() + " (" + failure.getMessage() + ").", reason);
            }
        }, logger);
    }

    private synchronized void adoptReplacement(EmbeddedServerHandle started) {
        if (stopped) return;
        handle = started;
        stopUpdateFailure();
        logger.info("The built-in panel restarted. Players can join again.");
    }

    private synchronized void failRestart(String failure, PanelRestart reason) {
        if (stopped) return;
        stopUpdateFailure();
        updateFailure = StartupFailureAnnouncer.start(logger, failure, reason.getRemedy());
    }

    private void stopUpdateFailure() {
        StartupFailureAnnouncer announcer = updateFailure;
        if (announcer != null) announcer.stop();
        updateFailure = null;
    }

    private synchronized BackendEndpoint connected(EmbeddedServerHandle started) {
        if (!stopped) handle = started;
        return started.endpoint();
    }

    private static CompletableFuture<BackendEndpoint> notRunning() {
        CompletableFuture<BackendEndpoint> failed = new CompletableFuture<>();
        failed.completeExceptionally(new EmbeddedServerException("The built-in panel is not running."));
        return failed;
    }

    private void abandon(CompletableFuture<EmbeddedServerHandle> launch) {
        if (!launch.cancel(false)) Futures.whenSucceeded(launch, EmbeddedServerHandle::close, logger);
    }

    private static Function<StartupProgress, CompletableFuture<EmbeddedServerHandle>> hostLauncher(
            EmbeddedSettings settings, String configPath, EmbeddedServerPaths paths, ManagedPayloads payloads,
            Map<String, Object> updateInputs, Map<String, String> forwardedSwitches, PluginLogger logger) {
        String internalApiKey = EmbeddedServerHost.newInternalApiKey();
        return progress -> EmbeddedServerHost.builder()
                .settings(settings)
                .paths(paths)
                .payloads(payloads)
                .internalApiKey(internalApiKey)
                .updateInputs(updateInputs)
                .forwardedSwitches(forwardedSwitches)
                .progress(progress)
                .configPath(configPath)
                .logger(logger)
                .build()
                .start();
    }

    @Value
    private static class PanelRestart {
        String starting;
        String notRunning;
        String notStopped;
        String notStarted;
        String remedy;
    }
}
