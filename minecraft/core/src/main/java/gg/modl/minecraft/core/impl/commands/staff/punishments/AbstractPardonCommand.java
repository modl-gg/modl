package gg.modl.minecraft.core.impl.commands.staff.punishments;

import revxrsal.commands.command.CommandActor;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.api.http.request.PardonPlayerRequest;
import gg.modl.minecraft.api.http.request.PardonPunishmentRequest;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.staff.action.AuthorizedStaff;
import gg.modl.minecraft.core.util.ApiFailures;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractPardonCommand {
    private static final Pattern PUNISHMENT_ID_PATTERN = Pattern.compile("^[A-Z0-9]+$");
    private static final int PUNISHMENT_ID_LENGTH = 8;

    private final HttpClientHolder httpClientHolder;
    private final Platform platform;
    private final Cache cache;
    private final LocaleManager localeManager;

    protected abstract @Nullable String getPunishmentType();

    protected void issuePardon(CommandActor actor, AuthorizedStaff issuer, String target, @Nullable String rawReason) {
        String type = getPunishmentType();
        String reason = normalizeReason(rawReason);
        String issuerName = CommandUtil.resolveActorName(actor, cache, platform);
        ActionIssuer actionIssuer = issuer.actionIssuer();

        if (isPunishmentId(target)) tryPunishmentIdWithFallback(actor, target, issuerName, actionIssuer, reason, type);
        else pardonByPlayerName(actor, target, issuerName, actionIssuer, reason, type);
    }

    private void pardonByPlayerName(CommandActor actor, String playerName, String issuerName, ActionIssuer actionIssuer, String reason, @Nullable String type) {

        String displayType = type != null ? type : "punishment";

        httpClientHolder.getClient().pardonPlayer(new PardonPlayerRequest(
            playerName, issuerName, actionIssuer, type, reason.isEmpty() ? null : reason
        )).thenAccept(response -> {
            if (response.hasPardoned()) {
                actor.reply(localeManager.getMessage("pardon.success_player",
                    mapOf("player", playerName, "type", displayType, "count", String.valueOf(response.getPardonedCount()))));
                invalidatePlayerCache(playerName, type);
            } else actor.reply(localeManager.getMessage("pardon.no_active_punishment",
                    mapOf("player", playerName, "type", displayType)));
        }).exceptionally(throwable -> {
            CommandUtil.handleException(actor, throwable, localeManager, "pardon.error");
            return null;
        });
    }

    private void tryPunishmentIdWithFallback(CommandActor actor, String target, String issuerName, ActionIssuer actionIssuer, String reason, @Nullable String expectedType) {
        PardonPunishmentRequest request = new PardonPunishmentRequest(
            target, issuerName, actionIssuer, reason.isEmpty() ? null : reason, expectedType
        );

        httpClientHolder.getClient().pardonPunishment(request).thenAccept(response -> {
            if (response.hasPardoned()) {
                actor.reply(localeManager.getMessage("pardon.success_id",
                    mapOf("id", target)));
                cache.endPunishment(target);
            } else actor.reply(localeManager.getMessage("pardon.already_pardoned_id",
                    mapOf("id", target)));
        }).exceptionally(throwable -> {
            Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;

            if (cause instanceof PanelUnavailableException) {
                actor.reply(ApiFailures.unavailable(localeManager, (PanelUnavailableException) cause));
                return null;
            }

            if (ApiClientException.isPermissionDenied(cause)) {
                actor.reply(localeManager.getMessage("general.no_permission"));
                return null;
            }

            if (cause instanceof ApiClientException && ((ApiClientException) cause).getStatusCode() == 404) {
                pardonByPlayerName(actor, target, issuerName, actionIssuer, reason, expectedType);
                return null;
            }

            String errorMessage = cause.getMessage();
            if (errorMessage != null && errorMessage.toLowerCase(Locale.ROOT).contains("type")) replyWrongType(actor, target, expectedType, errorMessage);
            else actor.reply(localeManager.getMessage("pardon.error",
                    mapOf("error", localeManager.sanitizeErrorMessage(errorMessage))));
            return null;
        });
    }

    private void replyWrongType(CommandActor actor, String target, @Nullable String expectedType, String errorMessage) {
        if ("ban".equals(expectedType)) actor.reply(localeManager.getMessage("pardon.error_wrong_type_ban",
                mapOf("id", target)));
        else if ("mute".equals(expectedType)) actor.reply(localeManager.getMessage("pardon.error_wrong_type_mute",
                mapOf("id", target)));
        else actor.reply(localeManager.getMessage("pardon.error",
                mapOf("error", localeManager.sanitizeErrorMessage(errorMessage))));
    }

    private boolean isPunishmentId(String target) {
        return target.length() == PUNISHMENT_ID_LENGTH && PUNISHMENT_ID_PATTERN.matcher(target).matches();
    }

    private static String normalizeReason(@Nullable String reason) {
        return reason == null ? "" : reason.trim();
    }

    private void invalidatePlayerCache(String playerName, @Nullable String type) {
        try {
            AbstractPlayer player = platform.getAbstractPlayer(playerName, false);
            if (player == null) return;

            CachedProfile profile = cache.getPlayerProfile(player.getUuid());
            if (profile == null) return;

            if ("ban".equals(type)) profile.setActiveBan(null);
            else if ("mute".equals(type)) profile.setActiveMute(null);
            else {
                profile.setActiveBan(null);
                profile.setActiveMute(null);
            }
        } catch (RuntimeException exception) {
            platform.getLogger().warning("Failed to invalidate cached punishment state for " + playerName + ": " + exception.getMessage());
        }
    }
}
