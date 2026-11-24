package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.connection.BackendClientIdentity;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.connection.BackendEndpointResolver;
import gg.modl.minecraft.core.connection.PlainHttpNotice;
import gg.modl.minecraft.core.connection.RemoteSettings;
import gg.modl.minecraft.core.member.MemberSession;
import gg.modl.minecraft.core.member.MemberSessionListener;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntToLongFunction;

public final class MemberLifecycle {
    static final long STATUS_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    static final String PLAYERS_CAN_JOIN = "Players can still join; the proxy enforces bans and mutes. "
            + "Staff tools on this server stay off until it connects.";

    private final BackendSource source;
    private final BackendClientIdentity identity;
    private final VersionMismatchReports mismatches = new VersionMismatchReports();
    private final Function<BackendConnections, StartupCall> startupCalls;
    private final BiFunction<BackendConnections, StartupResponse, MemberSession> sessions;
    private final MemberSessionListener listener;
    private final PluginLogger logger;
    private final IntToLongFunction retryDelaysMillis;
    private final StatusTimer statusTimer;
    private @Nullable BackendConnections connections;
    private @Nullable BackendConnector connector;
    private int connectorGeneration;
    private boolean outageReported;
    private volatile @Nullable MemberSession session;
    private @Nullable String degradedReason;
    private Runnable stopListeningForRefusals = MemberLifecycle::nothingScheduled;
    private Runnable stopStatusNotice = MemberLifecycle::nothingScheduled;
    private boolean started;
    private boolean stopped;

    MemberLifecycle(BackendSource source, BackendClientIdentity identity,
                    Function<BackendConnections, StartupCall> startupCalls,
                    BiFunction<BackendConnections, StartupResponse, MemberSession> sessions, MemberSessionListener listener,
                    PluginLogger logger, IntToLongFunction retryDelaysMillis, StatusTimer statusTimer) {
        this.source = source;
        this.identity = identity;
        this.startupCalls = startupCalls;
        this.sessions = sessions;
        this.listener = listener;
        this.logger = logger;
        this.retryDelaysMillis = retryDelaysMillis;
        this.statusTimer = statusTimer;
    }

    public static MemberLifecycle create(RemoteSettings remote, String configPath, StartupRequest request,
                                         BackendClientIdentity identity, boolean debugHttp, PluginLogger logger,
                                         MemberSessionListener listener) {
        StartupRequest memberRequest = memberRequest(request);
        BackendSource source = new RemoteBackendSource(remote, BackendEndpointResolver.fromEnvironment(),
                RemoteBackendSource.memberRemedy(configPath), logger, PlainHttpNotice.processWide(),
                RemoteBackendSource.OWN_THREAD);
        return new MemberLifecycle(source, identity, connections -> new StartupClient(connections, memberRequest),
                (connections, startup) -> MemberSession.open(connections, startup, debugHttp), listener, logger,
                BackendConnector.DEFAULT_RETRY_DELAYS_MILLIS, StatusTimer.DAEMON);
    }

    static StartupRequest memberRequest(StartupRequest request) {
        return request.withServerRole(StartupRequest.ServerRole.MEMBER);
    }

    static String noHubNotice(String serverUrl) {
        return "[network] The modl server at " + serverUrl
                + " does not run a network hub; staff tools on this server stay off until it is updated.";
    }

    static String realtimeDisabledNotice(String serverUrl) {
        return "[network] Realtime is disabled on the modl server at " + serverUrl
                + " (modl.realtime.ws.enabled=false); staff tools on this server stay off.";
    }

    static String degradedNotice(String reason) {
        return "[network] Staff tools on this server stay off: " + reason;
    }

    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        stopListeningForRefusals = mismatches.listen(refusal -> restartWhenConnected());
        Futures.whenComplete(source.endpoint(), this::onEndpointReady, logger);
    }

    public synchronized void restart(String reason) {
        BackendConnections current = connections;
        if (stopped || current == null) return;
        logger.debug("Reconnecting to the modl server at " + current.baseUrl() + ": " + reason);
        cancelConnector();
        endSession();
        degradedReason = null;
        refreshStatusNotice();
        startConnector(current);
    }

    public synchronized void degrade(String reason) {
        if (stopped) return;
        degradedReason = reason;
        listener.onDegraded(reason);
        refreshStatusNotice();
    }

    public ModlHttpClient httpClient() {
        MemberSession current = session;
        if (current == null) throw new IllegalStateException("this server is not connected to the modl server");
        return current.httpClient();
    }

    public synchronized void shutdown() {
        if (stopped) return;
        stopped = true;
        stopListeningForRefusals.run();
        cancelConnector();
        stopStatusNotice();
        endSession();
        source.shutdown();
    }

    private synchronized void restartWhenConnected() {
        if (session != null) restart("version mismatch");
    }

    private synchronized void onEndpointReady(BackendEndpoint ready, Throwable failure) {
        if (stopped) return;
        if (failure != null) {
            logger.warning("modl could not prepare the connection to the modl server", failure);
            return;
        }
        BackendConnections established = new BackendConnections(ready, identity, mismatches);
        connections = established;
        startConnector(established);
    }

    private void startConnector(BackendConnections target) {
        int generation = ++connectorGeneration;
        outageReported = false;
        BackendConnector started = new BackendConnector(startupCalls.apply(target), source.rejectedCredentialsRemedy(),
                logger, startup -> onConnected(generation, target, startup), retryDelaysMillis,
                () -> onFailureReported(generation), mismatch -> onMismatch(generation, mismatch));
        connector = started;
        started.connect();
    }

    private void onFailureReported(int generation) {
        synchronized (this) {
            if (stopped || generation != connectorGeneration || outageReported) return;
            outageReported = true;
        }
        logger.warning(PLAYERS_CAN_JOIN);
    }

    private synchronized void onMismatch(int generation, VersionMismatchFailure mismatch) {
        if (stopped || generation != connectorGeneration || mismatch.reason().equals(degradedReason)) return;
        logger.warning(mismatch.remedy());
        degrade(mismatch.reason());
    }

    private synchronized void onConnected(int generation, BackendConnections target, StartupResponse startup) {
        if (stopped || generation != connectorGeneration) return;
        connector = null;
        MemberSession connected = sessions.apply(target, startup);
        session = connected;
        degradedReason = null;
        refreshStatusNotice();
        listener.onConnected(connected);
    }

    private void cancelConnector() {
        connectorGeneration++;
        BackendConnector running = connector;
        if (running != null) running.cancel();
        connector = null;
    }

    private void endSession() {
        MemberSession ended = session;
        if (ended == null) return;
        session = null;
        try {
            listener.onDisconnected(ended);
        } finally {
            ended.close();
        }
    }

    private void refreshStatusNotice() {
        stopStatusNotice();
        Optional<String> notice = statusNotice();
        if (!notice.isPresent()) return;
        String line = notice.get();
        logger.warning(line);
        stopStatusNotice = statusTimer.every(STATUS_INTERVAL_MILLIS, () -> logger.warning(line));
    }

    private void stopStatusNotice() {
        stopStatusNotice.run();
        stopStatusNotice = MemberLifecycle::nothingScheduled;
    }

    private static void nothingScheduled() {
    }

    private Optional<String> statusNotice() {
        String reason = degradedReason;
        if (reason != null) return Optional.of(degradedNotice(reason));
        MemberSession current = session;
        if (current == null) return Optional.empty();
        String serverUrl = current.getConnections().baseUrl();
        if (!current.getStartup().enablesRealtime()) return Optional.of(realtimeDisabledNotice(serverUrl));
        if (!current.getStartup().runsNetworkHub()) return Optional.of(noHubNotice(serverUrl));
        return Optional.empty();
    }

    @FunctionalInterface
    interface StatusTimer {
        StatusTimer DAEMON = (periodMillis, task) -> {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "modl-member-status");
                thread.setDaemon(true);
                return thread;
            });
            ScheduledFuture<?> ticking = executor.scheduleAtFixedRate(task, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
            return () -> {
                ticking.cancel(false);
                executor.shutdownNow();
            };
        };

        Runnable every(long periodMillis, Runnable task);
    }
}
