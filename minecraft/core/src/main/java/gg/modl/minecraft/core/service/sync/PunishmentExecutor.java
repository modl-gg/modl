package gg.modl.minecraft.core.service.sync;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.Modification;
import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.request.PunishmentAcknowledgeRequest;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.locale.PunishmentMessageContext;
import gg.modl.minecraft.core.punishment.PunishmentMessageService;


import java.time.Instant;
import java.util.UUID;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.RecentIds;
import org.jspecify.annotations.Nullable;

class PunishmentExecutor {
    private static final int EXECUTED_PUNISHMENT_CAPACITY = 1024;

    private final Platform platform;
    private final HttpClientHolder httpClientHolder;
    private final Cache cache;
    private final PluginLogger logger;
    private final PunishmentMessageService punishmentMessages;
    private final boolean debugMode;
    private final RecentIds executed = new RecentIds(EXECUTED_PUNISHMENT_CAPACITY);

    PunishmentExecutor(Platform platform, HttpClientHolder httpClientHolder, Cache cache,
                       PluginLogger logger, PunishmentMessageService punishmentMessages, boolean debugMode) {
        this.platform = platform;
        this.httpClientHolder = httpClientHolder;
        this.cache = cache;
        this.logger = logger;
        this.punishmentMessages = punishmentMessages;
        this.debugMode = debugMode;
    }

    void processPendingPunishment(SyncResponse.PendingPunishment pending) {
        String playerUuid = pending.getMinecraftUuid();
        String username = pending.getUsername();
        SimplePunishment punishment = pending.getPunishment();

        if (debugMode) {
            logger.info(String.format("Processing pending punishment %s for %s - Type: '%s', Ordinal: %d, isBan: %s, isMute: %s, isKick: %s",
                    punishment.getId(), username, punishment.getType(), punishment.getOrdinal(),
                    punishment.isBan(), punishment.isMute(), punishment.isKick()));
        }

        platform.runOnMainThread(() -> {
            if (isAlreadyEnforced(playerUuid, punishment)) {
                acknowledgePunishment(punishment.getId(), playerUuid, true);
                return;
            }
            boolean success = executePunishment(playerUuid, username, punishment);
            if (success) executed.markIfNew(punishment.getId());
            acknowledgePunishment(punishment.getId(), playerUuid, success);
        });
    }

    private boolean isAlreadyEnforced(String playerUuid, SimplePunishment punishment) {
        if (executed.contains(punishment.getId())) return true;
        CachedProfile profile = cache.getPlayerProfile(UUID.fromString(playerUuid));
        return profile != null && (isCached(profile.getActiveMute(), punishment) || isCached(profile.getActiveBan(), punishment));
    }

    private static boolean isCached(@Nullable SimplePunishment cached, SimplePunishment delivered) {
        return cached != null && delivered.getId().equals(cached.getId());
    }

    void processModifiedPunishment(SyncResponse.ModifiedPunishment modified) {
        String playerUuid = modified.getMinecraftUuid();
        String username = modified.getUsername();
        SyncResponse.PunishmentWithModifications punishment = modified.getPunishment();

        platform.runOnMainThread(() -> {
            for (SyncResponse.PunishmentModification mod : punishment.getModifications()) {
                applyModification(playerUuid, username, punishment.getId(), mod);
            }
        });
    }

    private boolean executePunishment(String playerUuid, String username, SimplePunishment punishment) {
        try {
            UUID uuid = UUID.fromString(playerUuid);
            if (punishment.isBan()) return executeBan(uuid, username, punishment);
            if (punishment.isMute()) return executeMute(uuid, username, punishment);
            if (punishment.isKick()) return executeKick(uuid, username, punishment);

            logger.warning(String.format("Unknown punishment type for %s - Type: '%s', Ordinal: %d",
                    username, punishment.getType(), punishment.getOrdinal()));
            return false;
        } catch (Exception e) {
            logger.severe("Error executing punishment: " + e.getMessage());
            return false;
        }
    }

    private boolean executeMute(UUID uuid, String username, SimplePunishment punishment) {
        try {
            CachedProfile profile = cache.getPlayerProfile(uuid);
            if (profile != null) profile.setActiveMute(punishment);
            AbstractPlayer player = platform.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                platform.sendMessage(uuid, punishmentMessages.formatMuteMessage(punishment, PunishmentMessageContext.SYNC));
            }
            announce(username, punishment);
            if (debugMode) logger.info("Executed mute for " + username + ": " + punishment.getDescription());
            return true;
        } catch (Exception e) {
            logger.severe("Error executing mute: " + e.getMessage());
            return false;
        }
    }

    private boolean executeBan(UUID uuid, String username, SimplePunishment punishment) {
        try {
            AbstractPlayer player = platform.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                platform.kickPlayer(player, punishmentMessages.formatBanMessage(punishment, PunishmentMessageContext.SYNC));
            }
            announce(username, punishment);
            if (debugMode) logger.info("Executed ban for " + username + ": " + punishment.getDescription());
            return true;
        } catch (Exception e) {
            logger.severe("Error executing ban: " + e.getMessage());
            return false;
        }
    }

    private boolean executeKick(UUID uuid, String username, SimplePunishment punishment) {
        try {
            AbstractPlayer player = platform.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                if (debugMode) logger.info("Player " + username + " is not online, kick ignored");
                return true;
            }
            platform.kickPlayer(player, punishmentMessages.formatKickMessage(punishment, PunishmentMessageContext.SYNC));
            announce(username, punishment);
            if (debugMode) logger.info("Executed kick for " + username + ": " + punishment.getDescription());
            return true;
        } catch (Exception e) {
            logger.severe("Error executing kick: " + e.getMessage());
            return false;
        }
    }

    private void announce(String username, SimplePunishment punishment) {
        if (!punishment.isSilent()) platform.broadcast(punishmentMessages.formatPunishmentBroadcast(username, punishment));
    }

    private void applyModification(String playerUuid, String username, String punishmentId,
                                   SyncResponse.PunishmentModification modification) {
        try {
            UUID uuid = UUID.fromString(playerUuid);
            Modification.Type modType = Modification.Type.fromName(modification.getType());
            if (modType == null) {
                logger.warning("Unknown modification type: " + modification.getType());
                return;
            }

            if (modType.endsEnforcement()) {
                handleEnforcementEnded(uuid, username, punishmentId, modType);
            } else if (modType.isDurationChange()) {
                handleDurationChange(uuid, username, punishmentId, modification.getEffectiveDuration(), modification.getTimestamp());
            }
        } catch (Exception e) {
            logger.severe("Error handling punishment modification: " + e.getMessage());
        }
    }

    private void handleEnforcementEnded(UUID uuid, String username, String punishmentId, Modification.Type cause) {
        CachedProfile profile = cache.getPlayerProfile(uuid);
        if (profile != null) profile.endPunishment(punishmentId);
        if (debugMode) logger.info("Ended punishment " + punishmentId + " for " + username + " (" + cause + ")");
    }

    private void handleDurationChange(UUID uuid, String username, String punishmentId,
                                       @Nullable Long newDuration, @Nullable Long modificationTimestamp) {
        long changedAt = (modificationTimestamp != null) ? modificationTimestamp : System.currentTimeMillis();

        CachedProfile profile = cache.getPlayerProfile(uuid);
        if (profile != null) profile.changePunishmentDuration(punishmentId, newDuration, changedAt);

        if (debugMode) {
            String durationStr = newDuration != null ? newDuration + " ms" : "permanent";
            logger.info("Updated punishment " + punishmentId + " duration for " + username + " to " + durationStr);
        }
    }

    private void acknowledgePunishment(String punishmentId, String playerUuid, boolean success) {
        PunishmentAcknowledgeRequest request = new PunishmentAcknowledgeRequest(
                punishmentId, playerUuid, Instant.now().toString(), null, success);

        httpClientHolder.getClient().acknowledgePunishment(request)
                .thenAccept(response -> {
                    if (debugMode) logger.info("Acknowledged punishment " + punishmentId + ": " + (success ? "SUCCESS" : "FAILED"));
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (cause instanceof PanelUnavailableException) {
                        logger.warning("Failed to acknowledge punishment " + punishmentId + ": Panel temporarily unavailable");
                    } else {
                        logger.warning("Failed to acknowledge punishment " + punishmentId + ": " + throwable.getMessage());
                    }
                    return null;
                });
    }
}
