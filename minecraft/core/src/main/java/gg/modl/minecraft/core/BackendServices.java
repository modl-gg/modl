package gg.modl.minecraft.core;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.boot.BackendSession;
import gg.modl.minecraft.core.login.BanEnforcementAcknowledger;
import gg.modl.minecraft.core.login.InvalidNameNotice;
import gg.modl.minecraft.core.login.LoginBudget;
import gg.modl.minecraft.core.login.LoginFlow;
import gg.modl.minecraft.core.login.LoginService;
import gg.modl.minecraft.core.login.PlayerNotificationMapper;
import gg.modl.minecraft.core.network.link.NetworkCoreLink;
import gg.modl.minecraft.core.realtime.RealtimeConnection;
import gg.modl.minecraft.core.realtime.RealtimeTopics;
import gg.modl.minecraft.core.realtime.SyncPushHandler;
import gg.modl.minecraft.core.service.sync.SyncService;
import gg.modl.minecraft.core.service.sync.SyncServiceContext;
import gg.modl.minecraft.core.session.PlayerSessionService;
import gg.modl.minecraft.core.session.ServerSwitchService;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.TeardownStack;
import gg.modl.proto.modl.v1.ServerRole;
import gg.modl.proto.modl.v1.Topic;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Set;

@Getter
public final class BackendServices {
    private final @Nullable String serverInstanceId;
    private final SyncService syncService;
    private final LoginService loginService;
    private final LoginFlow loginFlow;
    private final PlayerSessionService playerSessionService;
    private final ServerSwitchService serverSwitchService;
    @Getter(AccessLevel.NONE)
    private final PluginLoader core;
    @Getter(AccessLevel.NONE)
    private final @Nullable RealtimeConnection realtimeConnection;
    @Getter(AccessLevel.NONE)
    private final @Nullable NetworkCoreLink networkCoreLink;
    @Getter(AccessLevel.NONE)
    private final TeardownStack teardown;
    @Getter(AccessLevel.NONE)
    private boolean stopped;

    static BackendServices create(PluginLoader core, BackendSession session) {
        TeardownStack teardown = new TeardownStack(core.getLogger());
        try {
            return new BackendServices(teardown, core, session);
        } catch (RuntimeException | Error failure) {
            teardown.unwind();
            throw failure;
        }
    }

    private BackendServices(TeardownStack teardown, PluginLoader core, BackendSession session) {
        this.teardown = teardown;
        this.core = core;
        this.serverInstanceId = session.serverInstanceId();
        PluginLogger logger = core.getLogger();
        Platform platform = core.getPlatform();
        HttpClientHolder httpClientHolder = core.getHttpClientHolder();

        HttpManager httpManager = new HttpManager(session.getConnections(), core.isDebugMode());
        teardown.push("stop the HTTP client", httpManager::shutdown);
        session.resolvePanelUrl(core.getPanelUrl());
        httpClientHolder.connect(httpManager.getHttpClient());
        teardown.push("disconnect the HTTP client", httpClientHolder::disconnect);
        core.getImportBackend().connect(session.getConnections());
        teardown.push("disconnect the import uploads", core.getImportBackend()::disconnect);
        if (core.isDebugMode()) {
            logger.info("modl configuration:");
            logger.info("  API URL: " + httpManager.getApiUrl());
        }

        this.syncService = new SyncService(SyncServiceContext.builder()
                .platform(platform)
                .httpClientHolder(httpClientHolder)
                .cache(core.getCache())
                .logger(logger)
                .localeManager(core.getLocaleManager())
                .panelUrl(core.getPanelUrl())
                .serverInstanceId(serverInstanceId)
                .debugMode(core.isDebugMode())
                .staff2faService(core.getStaff2faService())
                .chatCommandLogService(core.getChatCommandLogService())
                .punishmentMessageService(core.getPunishmentMessageService())
                .staffPermissionService(core.getStaffPermissionService())
                .staffAudiences(core.getStaffAudiences())
                .build());
        teardown.push("stop the sync service", syncService::stop);

        Set<Topic> syncTopics = grantedSyncTopics(session.getStartup());
        Set<Topic> networkTopics = platform.hostsNetworkMembers()
                ? RealtimeTopics.coreNetworkTopicsGrantedBy(session.getStartup())
                : Collections.<Topic>emptySet();
        this.realtimeConnection = createRealtimeConnection(httpManager, session.getStartup(), syncTopics, networkTopics);
        if (realtimeConnection != null) teardown.push("stop the realtime client", realtimeConnection::stop);
        this.networkCoreLink = realtimeConnection != null && !networkTopics.isEmpty()
                ? NetworkCoreLink.builder()
                        .connection(realtimeConnection)
                        .executor(core.getMemberActionExecutor())
                        .publisher(core.getModerationPublisher())
                        .availability(core.getNetworkAvailability())
                        .platform(platform)
                        .logger(logger)
                        .build()
                : null;

        core.loadServerState(syncService);

        this.loginService = new LoginService(core.getLocaleManager(), syncService, core.getMaintenanceService(),
                core.getCache(), core.getPunishmentMessageService(),
                new BanEnforcementAcknowledger(httpClientHolder, logger, core.isDebugMode()),
                new PlayerNotificationMapper(logger));
        this.loginFlow = new LoginFlow(httpClientHolder, core.getLoginCache(), loginService, core.getLoginSkins(),
                core.getForwardedAddressNotice(), new InvalidNameNotice(logger), serverInstanceId,
                LoginBudget.SECONDS);
        this.playerSessionService = new PlayerSessionService(platform, core.getCache(), core.getLocaleManager(),
                core.getStaff2faService(), syncService, httpClientHolder, core.getLoginCache(),
                core.getChatMessageCache(), core.getModeration(), core.getCachedProfileRegistry(), serverInstanceId);
        this.serverSwitchService = new ServerSwitchService(httpClientHolder, core.getCache(), core.getLocaleManager(),
                platform, serverInstanceId);
    }

    public synchronized void onShutdown(String description, Runnable undo) {
        if (stopped) throw new IllegalStateException("the backend services are already stopped");
        teardown.push(description, undo);
    }

    void start() {
        syncService.start();
        NetworkCoreLink link = networkCoreLink;
        if (link != null) onShutdown("stop the network link", link.install(syncService, core.getPluginServices()));
        startRealtimeConnection();
    }

    void shutdown() {
        synchronized (this) {
            stopped = true;
        }
        teardown.unwind();
    }

    private Set<Topic> grantedSyncTopics(StartupResponse startupResponse) {
        boolean localEnabled = core.getConfigManager().getRealtimeConfig() != null
                && core.getConfigManager().getRealtimeConfig().isEnabled();
        return localEnabled ? RealtimeTopics.syncTopicsGrantedBy(startupResponse) : Collections.<Topic>emptySet();
    }

    private @Nullable RealtimeConnection createRealtimeConnection(HttpManager httpManager, StartupResponse startupResponse,
                                                                  Set<Topic> syncTopics, Set<Topic> networkTopics) {
        Set<Topic> topics = RealtimeTopics.union(syncTopics, networkTopics);
        if (topics.isEmpty()) {
            if (core.isDebugMode()) {
                logger().info("[Realtime] Startup response did not enable realtime; staying on HTTP polling only");
            }
            return null;
        }
        try {
            RealtimeConnection connection = new RealtimeConnection(httpManager.getConnections(),
                    realtimeProtocolVersion(startupResponse), topics,
                    core.getPlatform()::getServerName, serverInstanceId, ServerRole.SERVER_ROLE_CORE, logger(),
                    core.isDebugMode());
            if (!syncTopics.isEmpty()) new SyncPushHandler(syncService, logger(), core.isDebugMode()).attachTo(connection);
            return connection;
        } catch (RuntimeException | LinkageError failure) {
            logger().warning("[Realtime] Failed to initialize realtime client; falling back to HTTP polling: " + failure);
            return null;
        }
    }

    private static int realtimeProtocolVersion(StartupResponse startupResponse) {
        Integer advertised = startupResponse.getRealtimeProtocolVersion();
        return advertised != null ? advertised : 1;
    }

    private void startRealtimeConnection() {
        RealtimeConnection connection = realtimeConnection;
        if (connection == null) return;
        try {
            connection.start();
            if (core.isDebugMode()) logger().info("[Realtime] Client started beside authoritative HTTP polling");
        } catch (RuntimeException | LinkageError failure) {
            logger().warning("[Realtime] Failed to start realtime client; falling back to HTTP polling: " + failure);
        }
    }

    private PluginLogger logger() {
        return core.getLogger();
    }
}
