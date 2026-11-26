package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.core.connection.BackendClientIdentity;
import gg.modl.minecraft.core.connection.PanelLocations;
import gg.modl.minecraft.core.connection.RemoteSettings;
import gg.modl.minecraft.core.member.MemberSessionListener;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

public final class MemberConnection {
    private final String configPath;
    private final PluginLogger logger;
    private final LifecycleFactory lifecycles;
    private final MemberLifecycle.StatusTimer statusTimer;
    private RemoteSettings settings;
    private @Nullable MemberLifecycle lifecycle;
    private Runnable stopMissingKeyNotice = MemberConnection::nothingScheduled;
    private boolean started;
    private boolean stopped;

    MemberConnection(RemoteSettings settings, String configPath, PluginLogger logger, LifecycleFactory lifecycles,
                     MemberLifecycle.StatusTimer statusTimer) {
        this.settings = settings;
        this.configPath = configPath;
        this.logger = logger;
        this.lifecycles = lifecycles;
        this.statusTimer = statusTimer;
    }

    public static MemberConnection create(RemoteSettings settings, String configPath, StartupRequest request,
                                          BackendClientIdentity identity, boolean debugHttp, PluginLogger logger,
                                          MemberSessionListener listener) {
        return new MemberConnection(settings, configPath, logger,
                remote -> MemberLifecycle.create(remote, configPath, request, identity, debugHttp, logger, listener),
                MemberLifecycle.StatusTimer.DAEMON);
    }

    static String missingKeyNotice(String configPath) {
        return "[network] This server has no server key yet (remote.api-key in " + configPath + " is empty), so modl stays"
                + " idle. Players can still join; the proxy enforces bans and mutes. Create a key in " + PanelLocations.ADD_SERVER
                + ", choose Behind a proxy, paste it into remote.api-key, and run /modlserver reload.";
    }

    static String connectingNotice(String serverUrl) {
        return "[network] Found a server key in remote.api-key; connecting to the modl server at " + serverUrl + ".";
    }

    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        connectOrWait();
    }

    public synchronized void reload(RemoteSettings next) {
        if (stopped || (next.equals(settings) && (lifecycle != null || !next.hasApiKey()))) return;
        settings = next;
        stopLifecycle();
        if (started) {
            if (next.hasApiKey()) logger.info(connectingNotice(next.getUrl()));
            connectOrWait();
        }
    }

    public synchronized void restart(String reason) {
        MemberLifecycle current = lifecycle;
        if (current != null) current.restart(reason);
    }

    public ModlHttpClient httpClient() {
        MemberLifecycle current;
        synchronized (this) {
            current = lifecycle;
        }
        if (current == null) throw new IllegalStateException("this server has no server key yet");
        return current.httpClient();
    }

    public synchronized boolean waitsForKey() {
        return started && !stopped && lifecycle == null;
    }

    public synchronized void shutdown() {
        if (stopped) return;
        stopped = true;
        stopLifecycle();
    }

    private void connectOrWait() {
        if (!settings.hasApiKey()) {
            String notice = missingKeyNotice(configPath);
            logger.warning(notice);
            stopMissingKeyNotice = statusTimer.every(MemberLifecycle.STATUS_INTERVAL_MILLIS, () -> logger.warning(notice));
            return;
        }
        MemberLifecycle created = lifecycles.create(settings);
        lifecycle = created;
        created.start();
    }

    private void stopLifecycle() {
        stopMissingKeyNotice.run();
        stopMissingKeyNotice = MemberConnection::nothingScheduled;
        MemberLifecycle current = lifecycle;
        if (current != null) current.shutdown();
        lifecycle = null;
    }

    private static void nothingScheduled() {
    }

    @FunctionalInterface
    interface LifecycleFactory {
        MemberLifecycle create(RemoteSettings settings);
    }
}
