package gg.modl.minecraft.core.service.sync;

import gg.modl.minecraft.api.PlayerNames;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.request.StatWipeAcknowledgeRequest;
import gg.modl.minecraft.api.http.request.SyncRequest;
import gg.modl.minecraft.api.http.response.PunishmentTypesResponse;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import lombok.Getter;
import lombok.Setter;

import static gg.modl.minecraft.core.util.Java8Collections.orTimeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;
import org.jspecify.annotations.Nullable;
import java.util.stream.Collectors;
import static gg.modl.minecraft.core.util.Java8Collections.listOf;

public class SyncService {
    private static final int INITIAL_SYNC_DELAY_SECONDS = 5;
    private static final long FALLBACK_FETCH_INTERVAL_SECONDS = 60, MAINTENANCE_INTERVAL_SECONDS = 60;
    private static final long SYNC_HTTP_TIMEOUT_SECONDS = 5, SYNC_TASK_TIMEOUT_SECONDS = 10, EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final Platform platform;
    private final HttpClientHolder httpClientHolder;
    private final Cache cache;
    private final PluginLogger logger;
    private final boolean debugMode;
    private final PanelUrl panelUrl;
    private final @Nullable String serverInstanceId;

    private final PunishmentExecutor punishmentExecutor;
    private final NotificationService notificationService;
    private final LogUploadService logUploadService;
    private final StaffSyncProcessor staffSyncProcessor;
    private final Staff2faSyncProcessor staff2faSyncProcessor;
    private final RefreshCoordinator refreshCoordinator;

    private volatile String lastSyncTimestamp = Instant.now().toString();
    private volatile @Nullable ScheduledExecutorService syncExecutor;
    private volatile @Nullable ExecutorService taskExecutor;
    @Getter @Setter private volatile @Nullable StatWipeExecutor statWipeExecutor;
    private volatile boolean isRunning = false;
    private volatile boolean realtimeConnected = false;
    private final AtomicBoolean forcedSyncPending = new AtomicBoolean(false);

    public SyncService(SyncServiceContext context) {
        this.platform = context.getPlatform();
        this.httpClientHolder = context.getHttpClientHolder();
        this.cache = context.getCache();
        this.logger = context.getLogger();
        this.debugMode = context.isDebugMode();
        this.panelUrl = context.getPanelUrl();
        this.serverInstanceId = context.getServerInstanceId();

        this.punishmentExecutor = new PunishmentExecutor(platform, httpClientHolder, cache, logger,
                context.getPunishmentMessageService(), debugMode);
        this.notificationService = new NotificationService(platform, httpClientHolder, cache, logger,
                context.getLocaleManager(), context.getPanelUrl(), context.getStaffAudiences(), debugMode);
        this.logUploadService = new LogUploadService(httpClientHolder, context.getChatCommandLogService(), logger, debugMode);
        this.staffSyncProcessor = new StaffSyncProcessor(platform, cache, logger, context.getLocaleManager(),
                context.getStaff2faService(), debugMode);
        this.staff2faSyncProcessor = new Staff2faSyncProcessor(platform, cache, logger, context.getLocaleManager(),
                context.getStaff2faService());
        this.refreshCoordinator = new RefreshCoordinator(context.getStaffPermissionService(), httpClientHolder, logger, debugMode);
    }

    public interface PunishmentTypesRefreshListener {
        void onPunishmentTypesRefreshed(List<PunishmentTypesResponse.PunishmentTypeData> types);
    }

    public void addPunishmentTypesListener(PunishmentTypesRefreshListener listener) {
        refreshCoordinator.addPunishmentTypesListener(listener);
    }

    public void start() {
        if (isRunning) {
            logger.warning("Sync service is already running");
            return;
        }

        this.lastSyncTimestamp = Instant.now().toString();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "modl-sync");
            t.setDaemon(true);
            return t;
        });
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.syncExecutor = scheduler;
        this.taskExecutor = new ThreadPoolExecutor(0, 4, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "modl-sync-task");
            t.setDaemon(true);
            return t;
        });
        notificationService.setExecutor(scheduler);
        Scheduling.withFixedDelay(scheduler, this::runFallbackFetchIfDisconnected,
                INITIAL_SYNC_DELAY_SECONDS, FALLBACK_FETCH_INTERVAL_SECONDS, TimeUnit.SECONDS, logger);
        Scheduling.withFixedDelay(scheduler, this::runMaintenance,
                MAINTENANCE_INTERVAL_SECONDS, MAINTENANCE_INTERVAL_SECONDS, TimeUnit.SECONDS, logger);
        logUploadService.start();
        isRunning = true;
        if (debugMode) logger.info("modl sync service started: websocket-push driven (fallback fetch every " + FALLBACK_FETCH_INTERVAL_SECONDS + "s while disconnected)");
    }

    public void stop() {
        if (!isRunning) return;

        logUploadService.stop();
        ScheduledExecutorService scheduler = syncExecutor;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) scheduler.shutdownNow();
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        ExecutorService tasks = taskExecutor;
        if (tasks != null) tasks.shutdownNow();

        isRunning = false;
        if (debugMode) logger.info("modl sync service stopped");
    }

    public void setRealtimeConnected(boolean connected) {
        this.realtimeConnected = connected;
    }

    private void runFallbackFetchIfDisconnected() {
        if (realtimeConnected) {
            if (debugMode) logger.info("Skipping fallback fetch; websocket connected");
            return;
        }
        performSync();
    }

    private void runMaintenance() {
        try {
            for (CachedProfile profile : cache.getRegistry().getAllProfiles()) {
                profile.cleanupExpiredNotifications();
            }
            PluginServices.chatInput().cleanupExpired();
        } catch (Exception e) {
            logger.warning("Sync maintenance pass failed: " + e.getMessage());
        }
    }

    public void forceSync(String reason) {
        runBaselineFetch(reason);
    }

    public void runBaselineFetch(String reason) {
        ScheduledExecutorService executor = syncExecutor;
        if (!isRunning || executor == null || executor.isShutdown()) {
            if (debugMode) logger.info("Skipping baseline fetch while sync service is stopped: " + reason);
            return;
        }

        try {
            if (!forcedSyncPending.compareAndSet(false, true)) {
                if (debugMode) logger.info("Coalescing baseline fetch while another is pending: " + reason);
                return;
            }
            executor.execute(() -> {
                try {
                    if (debugMode) logger.info("Running baseline fetch: " + reason);
                    performSync();
                } finally {
                    forcedSyncPending.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            forcedSyncPending.set(false);
            if (debugMode) logger.warning("Baseline fetch rejected: " + e.getMessage());
        }
    }

    private void performSync() {
        ExecutorService tasks = taskExecutor;
        if (tasks == null) return;
        final Callable<Void> work = () -> {
            try {
                Collection<AbstractPlayer> onlinePlayers = platform.getOnlinePlayers();
                SyncRequest request = buildSyncRequest(onlinePlayers);

                SyncResponse response = orTimeout(httpClientHolder.getClient().sync(request),
                    SYNC_HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
                handleSyncResponse(response, reportedIpsOf(request.getOnlinePlayers()));
            } catch (CompletionException e) {
                handleSyncException(e);
            } catch (Exception e) {
                logger.warning("Sync request failed: " + e.getMessage());
            } catch (Throwable t) {
                logger.severe("Error during sync: " + t.getMessage());
            }
            return null;
        };

        final Future<Void> future = tasks.submit(work);
        try {
            future.get(SYNC_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warning("Sync task timed out, cancelling...");
            future.cancel(true);
        } catch (Exception e) {
            logger.severe("Sync task failed: " + e.getMessage());
        }
    }

    private SyncRequest buildSyncRequest(Collection<AbstractPlayer> onlinePlayers) {
        return new SyncRequest(lastSyncTimestamp, buildOnlinePlayersList(onlinePlayers), platform.getServerName(),
                serverInstanceId, null, null);
    }

    static <T> List<T> filterByUsername(List<T> entries, Function<T, @Nullable String> usernameAccessor) {
        if (entries == null || entries.isEmpty()) return new ArrayList<>();
        List<T> retained = new ArrayList<>(entries.size());
        for (T entry : entries) {
            if (entry == null) continue;
            String username = usernameAccessor.apply(entry);
            if (username != null && PlayerNames.isValid(username)) retained.add(entry);
        }
        return retained;
    }

    private void handleSyncException(CompletionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof PanelUnavailableException) return;
        if (cause instanceof TimeoutException) logger.warning("Sync request timed out");
        else logger.warning("Sync request failed: " + cause.getMessage());
    }

    private List<SyncRequest.OnlinePlayer> buildOnlinePlayersList(Collection<AbstractPlayer> onlinePlayers) {
        return onlinePlayers.stream()
                .map(player -> {
                    CachedProfile profile = cache.getPlayerProfile(player.getUuid());
                    long sessionDuration = profile != null ? profile.getSessionDuration() : 0;
                    return new SyncRequest.OnlinePlayer(
                            player.getUuid().toString(),
                            player.getName(),
                            player.getIpAddress(),
                            sessionDuration
                    );
                })
                .collect(Collectors.toList());
    }

    static Map<UUID, @Nullable String> reportedIpsOf(List<SyncRequest.OnlinePlayer> reported) {
        Map<UUID, @Nullable String> ips = new HashMap<>();
        for (SyncRequest.OnlinePlayer player : reported) ips.put(UUID.fromString(player.getUuid()), player.getIpAddress());
        return ips;
    }

    private void handleSyncResponse(SyncResponse response, Map<UUID, @Nullable String> reportedIps) {
        this.lastSyncTimestamp = response.getTimestamp();
        SyncResponse.SyncData data = response.getData();
        if (panelUrl.update(data.getPanelUrl()) && debugMode) logger.info("Panel URL changed to " + panelUrl.get());

        for (SyncResponse.ModifiedPunishment modified : data.getRecentlyModifiedPunishments()) punishmentExecutor.processModifiedPunishment(modified);
        for (SyncResponse.PendingPunishment pending : data.getPendingPunishments()) punishmentExecutor.processPendingPunishment(pending);

        staffSyncProcessor.reconcileActiveStaff(data.getActiveStaffMembers(), reportedIps);

        for (SyncResponse.PlayerNotification notification : data.getPlayerNotifications()) notificationService.processPlayerNotification(notification);

        if (data.getStaffNotifications() != null) {
            for (SyncResponse.StaffNotification staffNotif : data.getStaffNotifications()) notificationService.processStaffNotification(staffNotif);
        }


        refreshCoordinator.onSyncTimestamps(data.getStaffPermissionsUpdatedAt(), data.getPunishmentTypesUpdatedAt());

        staff2faSyncProcessor.processVerifications(data.getStaff2faVerifications());
    }

    public boolean isStatWipeAvailable() {
        return statWipeExecutor != null;
    }

    public void executeStatWipeFromLogin(SyncResponse.PendingStatWipe statWipe) {
        StatWipeExecutor executor = statWipeExecutor;
        if (executor == null) return;
        logger.debug("[stat-wipe] Executing for " + statWipe.getUsername() + " (punishment: " + statWipe.getPunishmentId() + ")");

        executor.executeStatWipe(
                statWipe.getUsername(), statWipe.getMinecraftUuid(), statWipe.getPunishmentId(),
                (outcome, serverName) -> handleStatWipeResult(statWipe, outcome, serverName)
        );
    }

    void handleStatWipeResult(SyncResponse.PendingStatWipe statWipe, StatWipeOutcome outcome, @Nullable String serverName) {
        if (outcome == StatWipeOutcome.FAILED) {
            logger.warning("[stat-wipe] Failed for " + statWipe.getUsername() + ", will retry on next sync");
            return;
        }
        if (outcome == StatWipeOutcome.DELEGATED) {
            logger.debug("[stat-wipe] Handed to the servers behind this proxy for " + statWipe.getUsername()
                    + "; they acknowledge it themselves");
            return;
        }
        logger.debug("[stat-wipe] Completed for " + statWipe.getUsername() + " on " + serverName + ", acknowledging");
        httpClientHolder.getClient().acknowledgeStatWipe(
                new StatWipeAcknowledgeRequest(statWipe.getPunishmentId(), serverName, true))
                .exceptionally(throwable -> {
                    logger.warning("[stat-wipe] Failed to acknowledge for " + statWipe.getPunishmentId() + ": " + throwable.getMessage());
                    return null;
                });
    }

    public void deliverPendingNotifications(UUID playerUuid) {
        notificationService.deliverPendingNotifications(playerUuid);
    }

    public boolean submitRealtimeApply(Runnable apply) {
        ScheduledExecutorService executor = syncExecutor;
        if (!isRunning || executor == null || executor.isShutdown()) return false;
        try {
            executor.execute(apply);
            return true;
        } catch (RejectedExecutionException e) {
            if (debugMode) logger.warning("Realtime apply rejected: " + e.getMessage());
            return false;
        }
    }

    public void applyPendingPunishment(SyncResponse.PendingPunishment pending) {
        punishmentExecutor.processPendingPunishment(pending);
    }

    public void applyModifiedPunishment(SyncResponse.ModifiedPunishment modified) {
        punishmentExecutor.processModifiedPunishment(modified);
    }

    public void applyPlayerNotification(SyncResponse.PlayerNotification notification) {
        notificationService.processPlayerNotification(notification);
    }

    public void applyStaffNotification(SyncResponse.StaffNotification notification) {
        notificationService.processStaffNotification(notification);
    }

    public void applyStaff2faVerification(SyncResponse.Staff2faVerification verification) {
        staff2faSyncProcessor.processVerifications(listOf(verification));
    }

    public void applyActiveStaffMember(SyncResponse.ActiveStaffMember staffMember) {
        staffSyncProcessor.processActiveStaffMember(staffMember, Collections.emptyMap());
    }

    public void applyStatWipe(SyncResponse.PendingStatWipe statWipe) {
        executeStatWipeFromLogin(statWipe);
    }

    public void refreshStaffPermissionsNow() {
        refreshCoordinator.refreshStaffPermissions();
    }

    public void refreshPunishmentTypesNow() {
        refreshCoordinator.refreshPunishmentTypes();
    }
}
