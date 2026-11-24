package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.core.connection.BackendClientIdentity;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.connection.ConnectionConfig;
import gg.modl.minecraft.core.connection.ConnectionConfigException;
import gg.modl.minecraft.core.connection.ConnectionConfigLoader;
import gg.modl.minecraft.core.connection.GamePortGuard;
import gg.modl.minecraft.core.connection.ProxyForwarding;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.IntToLongFunction;

public final class PluginLifecycle {
    private final PluginHost host;
    private final PluginLogger logger;
    private final ConnectionConfigLoader configLoader;
    private final Function<ConnectionConfig, BackendSource> sources;
    private final IntToLongFunction retryDelaysMillis;
    private final BackendClientIdentity identity;
    private final VersionMismatchReports mismatches = new VersionMismatchReports();
    private final LoginGate gate = new LoginGate();
    private final CoreAvailability availability = () -> this.state;
    private volatile CoreState state = CoreState.STARTING;
    private @Nullable BootLocale locale;
    private boolean loaded;
    private @Nullable ConnectionConfig config;
    private @Nullable StartupFailure loadFailure;
    private @Nullable BackendSource source;
    private @Nullable CoreBootstrap bootstrap;
    private @Nullable BackendConnector connector;
    private boolean corePrepared;
    private @Nullable StartupFailureAnnouncer failureAnnouncer;
    private boolean gateRegistered;
    private boolean stopped;
    private @Nullable VersionMismatchFailure shownMismatch;
    private @Nullable BackendConnections runningConnections;
    private @Nullable CompatibilityProbe probe;
    private Runnable stopListeningForRefusals = PluginLifecycle::nothingToStop;

    public PluginLifecycle(PluginHost host, ProxyForwarding forwarding) {
        this(host, new ConnectionConfigLoader(host.dataFolder(), host.platformType(), forwarding, host.logger()));
    }

    private PluginLifecycle(PluginHost host, ConnectionConfigLoader configLoader) {
        this(host, configLoader,
                loaded -> BackendSource.forConfig(loaded, host.dataFolder(), configLoader.displayPath(), host.logger()),
                BackendConnector.DEFAULT_RETRY_DELAYS_MILLIS);
    }

    PluginLifecycle(PluginHost host, ConnectionConfigLoader configLoader, Function<ConnectionConfig, BackendSource> sources,
                    IntToLongFunction retryDelaysMillis) {
        this.host = host;
        this.logger = host.logger();
        this.configLoader = configLoader;
        this.sources = sources;
        this.retryDelaysMillis = retryDelaysMillis;
        this.identity = BackendClientIdentity.newBoot(host.platformType());
    }

    public synchronized @Nullable ConnectionConfig load(Runnable prerequisites) {
        if (loaded) return config;
        loaded = true;
        try {
            prerequisites.run();
            Optional<StartupFailure> unmetRequirement = host.unmetRequirement();
            if (unmetRequirement.isPresent()) {
                recordLoadFailure(unmetRequirement.get());
                return config;
            }
            ConnectionConfig loadedConfig = configLoader.load();
            GamePortGuard.requireOwnPanelPort(loadedConfig, host.gamePorts(), host.rconPorts());
            if (loadedConfig.getMode().runsCore()) {
                BackendSource started = sources.apply(loadedConfig);
                source = started;
                started.start();
            }
            config = loadedConfig;
        } catch (ConnectionConfigException invalid) {
            recordLoadFailure(invalid.getMessage(), "Edit " + configPath() + " and restart the " + serverNoun() + ".");
        } catch (RuntimeException | LinkageError failure) {
            logger.warning("modl failed to load", failure);
            recordLoadFailure("modl failed to load (" + describe(failure) + ").", checkErrorsRemedy());
        }
        return config;
    }

    public synchronized @Nullable ConnectionConfig config() {
        return config;
    }

    public String configPath() {
        return configLoader.displayPath();
    }

    public ConnectionConfig reloadConnectionConfig() {
        return configLoader.load();
    }

    public synchronized void enable(StartupRequest request, CoreBootstrap coreBootstrap) {
        if (stopped || bootstrap != null) return;
        bootstrap = coreBootstrap;
        denyLogins(CoreState.STARTING.refusalMessageKey(), Collections.<String, String>emptyMap());
        try {
            prepareAndConnect(request, coreBootstrap);
        } catch (RuntimeException | LinkageError failure) {
            failToStart(failure);
        }
    }

    public CoreAvailability availability() {
        return availability;
    }

    public void failClosed(String reason, String remedy) {
        failClosed(StartupFailure.of(reason, remedy));
    }

    public synchronized void failClosed(StartupFailure failure) {
        if (stopped) return;
        state = CoreState.UNAVAILABLE;
        cancelConnector();
        stopListeningForRefusals.run();
        stopProbe();
        denyLogins(failure.getLoginMessageKey(), failure.getLoginMessagePlaceholders());
        stopFailureAnnouncer();
        failureAnnouncer = StartupFailureAnnouncer.start(logger, failure.getReason(), failure.getRemedy());
    }

    public synchronized void shutdown() {
        stopped = true;
        state = CoreState.UNAVAILABLE;
        cancelConnector();
        stopFailureAnnouncer();
        stopListeningForRefusals.run();
        stopProbe();
        if (corePrepared) stopCore();
        corePrepared = false;
        allowLogins();
        BackendSource running = source;
        if (running != null) running.shutdown();
        source = null;
    }

    private void prepareAndConnect(StartupRequest request, CoreBootstrap coreBootstrap) {
        if (!loaded) load(PluginLifecycle::nothingToStop);
        StartupFailure loadFailed = loadFailure;
        if (loadFailed != null) {
            failClosed(loadFailed);
            return;
        }
        BackendSource connecting = source;
        if (connecting == null) throw new IllegalStateException(modeName() + " does not run the modl core");
        prepareCore(coreBootstrap);
        CompletableFuture<BackendEndpoint> endpoint = connecting.endpoint();
        Futures.whenComplete(endpoint, (ready, failure) -> connectOnceAvailable(request, connecting, endpoint), logger);
    }

    private void prepareCore(CoreBootstrap coreBootstrap) {
        try {
            coreBootstrap.prepare(availability);
        } catch (RuntimeException | LinkageError failure) {
            stopCore();
            throw failure;
        }
        corePrepared = true;
    }

    private synchronized void connectOnceAvailable(StartupRequest request, BackendSource connecting,
                                                   CompletableFuture<BackendEndpoint> endpoint) {
        if (stopped || connecting != source) return;
        try {
            Optional<BackendEndpoint> available = availableEndpoint(endpoint);
            if (!available.isPresent()) return;
            BackendConnections connections = new BackendConnections(available.get(), identity, mismatches);
            BackendConnector started = startupConnector(new StartupClient(connections, request), connections, connecting);
            connector = started;
            started.connect();
        } catch (RuntimeException | LinkageError failure) {
            failToStart(failure);
        }
    }

    private Optional<BackendEndpoint> availableEndpoint(CompletableFuture<BackendEndpoint> endpoint) {
        try {
            return Optional.of(endpoint.join());
        } catch (CompletionException | CancellationException unavailable) {
            Throwable wrapped = unavailable.getCause();
            Throwable cause = wrapped != null ? wrapped : unavailable;
            failClosed(describe(cause), "Fix the problem and restart the " + serverNoun()
                    + ", or connect to a separately hosted modl server with mode: remote in " + configPath() + ".");
            return Optional.empty();
        }
    }

    private BackendConnector startupConnector(StartupClient client, BackendConnections connections,
                                              BackendSource connecting) {
        return new BackendConnector(client, connecting.rejectedCredentialsRemedy(), logger,
                startup -> onConnected(BackendSession.create(connections, startup, connecting)), retryDelaysMillis,
                () -> { }, this::showStartupMismatch);
    }

    private void onConnected(BackendSession session) {
        synchronized (this) {
            if (stopped) return;
        }
        try {
            host.runOnMainThread(() -> startCore(session));
        } catch (RuntimeException schedulerStopped) {
            logger.debug("The modl start was not scheduled because the plugin is stopping: " + schedulerStopped);
        }
    }

    private synchronized void startCore(BackendSession session) {
        CoreBootstrap core = bootstrap;
        if (stopped || state != CoreState.STARTING || core == null) return;
        try {
            core.connect(session);
        } catch (RuntimeException | LinkageError failure) {
            failToStart(failure);
            return;
        }
        state = CoreState.RUNNING;
        connector = null;
        stopFailureAnnouncer();
        allowLogins();
        shownMismatch = null;
        runningConnections = session.getConnections();
        stopListeningForRefusals.run();
        stopListeningForRefusals = mismatches.listen(this::onRefusedWhileRunning);
    }

    private synchronized void showStartupMismatch(VersionMismatchFailure mismatch) {
        if (stopped || state != CoreState.STARTING || mismatch.sameAs(shownMismatch)) return;
        shownMismatch = mismatch;
        denyLogins(CoreState.INCOMPATIBLE.refusalMessageKey(), Collections.<String, String>emptyMap());
        announce(mismatch);
    }

    private synchronized void onRefusedWhileRunning(VersionMismatchFailure mismatch) {
        BackendConnections connections = runningConnections;
        if (stopped || state != CoreState.RUNNING || connections == null) return;
        state = CoreState.INCOMPATIBLE;
        announce(mismatch);
        probe = CompatibilityProbe.start(connections, logger, () -> onMainThread(this::resumeCompatible));
        onMainThread(this::denyWhileIncompatible);
    }

    private synchronized void denyWhileIncompatible() {
        if (stopped || state != CoreState.INCOMPATIBLE) return;
        denyLogins(CoreState.INCOMPATIBLE.refusalMessageKey(), Collections.<String, String>emptyMap());
    }

    private synchronized void resumeCompatible() {
        if (stopped || state != CoreState.INCOMPATIBLE) return;
        state = CoreState.RUNNING;
        stopProbe();
        stopFailureAnnouncer();
        allowLogins();
        logger.info("The modl server accepts this plugin again.");
    }

    private void onMainThread(Runnable task) {
        try {
            host.runOnMainThread(task);
        } catch (RuntimeException schedulerStopped) {
            logger.debug("A compatibility change was not scheduled because the plugin is stopping: " + schedulerStopped);
        }
    }

    private void announce(VersionMismatchFailure mismatch) {
        stopFailureAnnouncer();
        failureAnnouncer = StartupFailureAnnouncer.start(logger, mismatch.reason(), mismatch.remedy());
    }

    private void stopProbe() {
        CompatibilityProbe running = probe;
        if (running != null) running.stop();
        probe = null;
    }

    private void failToStart(Throwable failure) {
        logger.warning("modl failed to start", failure);
        failClosed("modl failed to start (" + describe(failure) + ").", checkErrorsRemedy());
    }

    private void stopCore() {
        CoreBootstrap core = bootstrap;
        if (core == null) return;
        try {
            core.stop();
        } catch (RuntimeException | LinkageError failure) {
            logger.warning("modl did not stop cleanly", failure);
        }
    }

    private void recordLoadFailure(String reason, String remedy) {
        recordLoadFailure(StartupFailure.of(reason, remedy));
    }

    private void recordLoadFailure(StartupFailure failure) {
        config = null;
        loadFailure = failure;
        logger.severe(failure.getReason());
    }

    private void cancelConnector() {
        BackendConnector running = connector;
        if (running != null) running.cancel();
        connector = null;
    }

    private void stopFailureAnnouncer() {
        StartupFailureAnnouncer announcer = failureAnnouncer;
        if (announcer != null) announcer.stop();
        failureAnnouncer = null;
    }

    private void denyLogins(String messageKey, Map<String, String> placeholders) {
        if (!gateRegistered) {
            host.registerLoginGate(gate);
            gateRegistered = true;
        }
        try {
            gate.deny(locale().message(messageKey, placeholders));
        } catch (RuntimeException | LinkageError unavailable) {
            logger.warning("The login message " + messageKey + " could not be loaded: " + describe(unavailable));
        }
    }

    private void allowLogins() {
        gate.open();
        if (!gateRegistered) return;
        host.unregisterLoginGate();
        gateRegistered = false;
    }

    private BootLocale locale() {
        BootLocale loadedLocale = locale;
        if (loadedLocale == null) {
            loadedLocale = BootLocale.load(host.dataFolder(), logger);
            locale = loadedLocale;
        }
        return loadedLocale;
    }

    private String checkErrorsRemedy() {
        return "Check the errors above and restart the " + serverNoun() + ".";
    }

    private String serverNoun() {
        return host.platformType().isProxy() ? "proxy" : "server";
    }

    private String modeName() {
        ConnectionConfig loadedConfig = config;
        return loadedConfig == null ? "this connection mode" : "mode " + loadedConfig.getMode().yamlName();
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message != null ? message : failure.getClass().getSimpleName();
    }

    private static void nothingToStop() {
    }
}
