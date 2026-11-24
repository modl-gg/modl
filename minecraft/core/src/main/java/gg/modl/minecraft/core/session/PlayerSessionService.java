package gg.modl.minecraft.core.session;

import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.PlayerDisconnectRequest;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.cache.LoginCache;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.network.LeftNetworkOutcome;
import gg.modl.minecraft.core.network.ModerationSessionService;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.service.Staff2faService;
import gg.modl.minecraft.core.service.sync.SyncService;
import gg.modl.minecraft.core.staff.PermissionUtil;
import gg.modl.minecraft.core.util.Futures;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class PlayerSessionService {
    private final Platform platform;
    private final Cache cache;
    private final LocaleManager localeManager;
    private final Staff2faService staff2faService;
    private final SyncService syncService;
    private final HttpClientHolder httpClientHolder;
    private final LoginCache loginCache;
    private final ChatMessageCache chatMessageCache;
    private final ModerationSessionService moderation;
    private final CachedProfileRegistry registry;
    private final @Nullable String serverInstanceId;

    public PlayerSessionService(Platform platform, Cache cache, LocaleManager localeManager,
                                Staff2faService staff2faService, SyncService syncService,
                                HttpClientHolder httpClientHolder, LoginCache loginCache,
                                ChatMessageCache chatMessageCache, ModerationSessionService moderation,
                                CachedProfileRegistry registry, @Nullable String serverInstanceId) {
        this.platform = platform;
        this.cache = cache;
        this.localeManager = localeManager;
        this.staff2faService = staff2faService;
        this.syncService = syncService;
        this.httpClientHolder = httpClientHolder;
        this.loginCache = loginCache;
        this.chatMessageCache = chatMessageCache;
        this.moderation = moderation;
        this.registry = registry;
        this.serverInstanceId = serverInstanceId;
    }

    public void handlePlayerJoin(UUID uuid, String playerName) {
        if (!platform.isOnline(uuid)) {
            return;
        }

        cache.getRegistry().createProfile(uuid);

        if (staff2faService != null && staff2faService.isEnabled() && PermissionUtil.isStaff(uuid, cache)) staff2faService.onStaffJoin(uuid);

        if (PermissionUtil.isStaff(uuid, cache)
                && (staff2faService == null || !staff2faService.isEnabled() || staff2faService.isAuthenticated(uuid))) {
            String panelName = cache.getStaffDisplayName(uuid);
            if (panelName == null) panelName = playerName;
            platform.staffBroadcast(localeManager.getMessage("staff_notifications.join",
                    mapOf("staff", panelName, "in-game-name", playerName, "server", platform.getServerName())));
        }

        syncService.deliverPendingNotifications(uuid);
    }

    public void handlePlayerDisconnect(UUID uuid, String playerName) {
        ModlHttpClient httpClient = httpClientHolder.getClient();

        CachedProfile profile = registry.getProfile(uuid);
        long sessionDuration = profile != null ? profile.getSessionDuration() : 0;
        PlayerDisconnectRequest request = new PlayerDisconnectRequest(uuid.toString(), sessionDuration, serverInstanceId);
        Futures.ignoreResult(httpClient.playerDisconnect(request), platform.getLogger());

        if (PermissionUtil.isStaff(uuid, cache)) {
            String displayName = cache.getDisplayName(uuid, playerName);
            platform.staffBroadcast(localeManager.getMessage("staff_notifications.leave",
                    mapOf("staff", displayName, "in-game-name", playerName)));
            Futures.ignoreResult(httpClient.reportStaffDisconnect(uuid.toString(), sessionDuration), platform.getLogger());
        }

        releaseModerationState(playerName, moderation.playerLeftNetwork(uuid));

        ReplayService replayService = PluginServices.replay();
        if (replayService != null) {
            replayService.onPlayerDisconnect(uuid);
        }

        registry.destroyProfile(uuid);
        cache.setOffline(uuid);
        loginCache.invalidateLoginResult(uuid);
        chatMessageCache.removePlayer(uuid.toString());
        PluginServices.chatInput().clearOnDisconnect(uuid);
    }

    private void releaseModerationState(String playerName, LeftNetworkOutcome left) {
        if (left.isFrozen()) {
            platform.staffBroadcast(localeManager.getMessage("freeze.logout_notification", mapOf("player", playerName)));
        }
        String targetLeft = localeManager.getMessage("target.target_left", mapOf("player", playerName));
        for (UUID staff : left.getStaffWhoseTargetLeft()) platform.sendMessage(staff, targetLeft);
    }
}
