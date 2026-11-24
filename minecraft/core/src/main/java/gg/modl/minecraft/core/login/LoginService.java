package gg.modl.minecraft.core.login;

import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.api.http.response.PlayerLoginResponse;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.locale.PunishmentMessageContext;
import gg.modl.minecraft.core.punishment.PunishmentMessageService;
import gg.modl.minecraft.core.service.MaintenanceService;
import gg.modl.minecraft.core.service.sync.SyncService;
import gg.modl.minecraft.core.util.ApiFailures;
import gg.modl.minecraft.core.util.Throwables;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeoutException;

public final class LoginService {
    private static final String BAN_CHECK_FAILED_KEY = "api_errors.ban_check_failed",
            CONNECTION_FAILED_KEY = "api_errors.connection_failed", INVALID_NAME_KEY = "api_errors.invalid_name",
            CHECK_SKIPPED_KEY = "api_errors.login_check_skipped";

    private final LocaleManager localeManager;
    private final SyncService syncService;
    private final MaintenanceService maintenanceService;
    private final Cache cache;
    private final PunishmentMessageService punishmentMessageService;
    private final BanEnforcementAcknowledger banEnforcementAcknowledger;
    private final PlayerNotificationMapper notificationMapper;

    public LoginService(LocaleManager localeManager, SyncService syncService, MaintenanceService maintenanceService,
                        Cache cache, PunishmentMessageService punishmentMessageService,
                        BanEnforcementAcknowledger banEnforcementAcknowledger, PlayerNotificationMapper notificationMapper) {
        this.localeManager = localeManager;
        this.syncService = syncService;
        this.maintenanceService = maintenanceService;
        this.cache = cache;
        this.punishmentMessageService = punishmentMessageService;
        this.banEnforcementAcknowledger = banEnforcementAcknowledger;
        this.notificationMapper = notificationMapper;
    }

    public interface LoginResult {
        @Data @AllArgsConstructor final class Allowed implements LoginResult {
            private final PlayerLoginResponse response;
        }
        @Data @AllArgsConstructor final class Denied implements LoginResult {
            private final String message;
        }
    }

    public LoginResult processLoginResponse(PlayerLoginResponse response, UUID playerUuid) {
        SimplePunishment ban = response.getActiveBan();
        if (ban != null) {
            String message = punishmentMessageService.formatBanMessage(ban, PunishmentMessageContext.LOGIN);
            if (!ban.isStarted()) {
                banEnforcementAcknowledger.acknowledge(ban, playerUuid.toString());
            }
            return new LoginResult.Denied(message);
        }

        if (syncService.isStatWipeAvailable() && response.hasPendingStatWipes()) {
            for (SyncResponse.PendingStatWipe statWipe : response.getPendingStatWipes()) {
                syncService.executeStatWipeFromLogin(statWipe);
            }
            return new LoginResult.Denied(localeManager.getMessage("stat_wipe.kick_message"));
        }

        if (maintenanceService.isEnabled() && !maintenanceService.canJoin(playerUuid, cache)) {
            return new LoginResult.Denied(localeManager.getMessage("maintenance.login_denied"));
        }

        return new LoginResult.Allowed(response);
    }

    public LoginResult handleLoginError(Exception error) {
        Throwable cause = Throwables.rootCause(error);
        if (cause instanceof TimeoutException || cause instanceof InterruptedIOException) {
            return new LoginResult.Denied(localeManager.getMessage(CONNECTION_FAILED_KEY));
        }
        if (ApiFailures.isDatabaseMoving(cause)) {
            return new LoginResult.Denied(localeManager.getMessage(ApiFailures.DATABASE_MOVING_KEY));
        }

        return denyUntilBanStatusVerifiable();
    }

    public LoginResult refuseInvalidName() {
        return new LoginResult.Denied(localeManager.getMessage(INVALID_NAME_KEY));
    }

    public String uncheckedLoginMessage() {
        return localeManager.getMessage(CHECK_SKIPPED_KEY);
    }

    private LoginResult denyUntilBanStatusVerifiable() {
        return new LoginResult.Denied(unverifiableBanStatusMessage());
    }

    public String unverifiableBanStatusMessage() {
        return localeManager.getMessage(BAN_CHECK_FAILED_KEY);
    }

    public @Nullable String denialMessage(LoginResult result) {
        if (result instanceof LoginResult.Allowed) return null;
        if (result instanceof LoginResult.Denied) {
            String message = ((LoginResult.Denied) result).getMessage();
            if (message != null && !message.isEmpty()) return message;
        }
        return unverifiableBanStatusMessage();
    }

    public void cacheLoginData(UUID uuid, @Nullable PlayerLoginResponse response) {
        if (response == null) return;

        CachedProfile profile = cache.getPlayerProfile(uuid);
        if (profile == null) return;

        SimplePunishment mute = response.getActiveMute();
        if (mute != null) profile.setActiveMute(mute);

        if (response.hasNotifications()) {
            for (Map<String, Object> notificationData : response.getPendingNotifications()) {
                SyncResponse.PlayerNotification notification =
                        notificationMapper.mapToPlayerNotification(notificationData);
                if (notification != null) {
                    profile.addNotification(notification);
                }
            }
        }
    }
}
