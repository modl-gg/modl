package gg.modl.minecraft.features.statwipe;

import gg.modl.minecraft.api.PlayerNames;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.StatWipeAcknowledgeRequest;
import gg.modl.minecraft.core.network.MemberStatWipes;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.RecentIds;
import gg.modl.minecraft.features.FeatureScheduler;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class MemberStatWipeHandler implements MemberStatWipes {
    static final int RECENTLY_ACKNOWLEDGED = 1024;
    private static final Pattern PUNISHMENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final StatWipeHandler commands;
    private final FeatureScheduler scheduler;
    private final Supplier<ModlHttpClient> httpClient;
    private final Supplier<String> serverName;
    private final PluginLogger logger;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final RecentIds acknowledged = new RecentIds(RECENTLY_ACKNOWLEDGED);

    public MemberStatWipeHandler(StatWipeHandler commands, FeatureScheduler scheduler, Supplier<ModlHttpClient> httpClient,
                                 Supplier<String> serverName, PluginLogger logger) {
        this.commands = commands;
        this.scheduler = scheduler;
        this.httpClient = httpClient;
        this.serverName = serverName;
        this.logger = logger;
    }

    @Override
    public boolean execute(String username, UUID uuid, String punishmentId) {
        if (!PlayerNames.isValid(username)
                || punishmentId == null || !PUNISHMENT_ID.matcher(punishmentId).matches()) {
            logger.warning("[stat-wipe] Skipped a stat wipe with an invalid player name or punishment id from the modl network");
            return false;
        }
        if (acknowledged.contains(punishmentId) || !inFlight.add(punishmentId)) return false;
        logger.debug("[stat-wipe] Processing stat-wipe for " + username + " (punishment: " + punishmentId + ")");
        scheduler.runOnMainThread(() -> {
            boolean wiped;
            try {
                wiped = commands.execute(username, uuid.toString(), punishmentId);
            } catch (RuntimeException failure) {
                logger.warning("[stat-wipe] Stat-wipe for " + username + " failed: " + failure);
                wiped = false;
            }
            if (wiped) acknowledge(punishmentId);
            else inFlight.remove(punishmentId);
        });
        return true;
    }

    private void acknowledge(String punishmentId) {
        try {
            Futures.whenComplete(httpClient.get().acknowledgeStatWipe(
                    new StatWipeAcknowledgeRequest(punishmentId, serverName.get(), true)), (ignored, failure) -> {
                        inFlight.remove(punishmentId);
                        if (failure == null) acknowledged.markIfNew(punishmentId);
                        else logger.warning("[stat-wipe] Failed to acknowledge for " + punishmentId + ": " + failure.getMessage());
                    }, logger);
        } catch (IllegalStateException disconnected) {
            inFlight.remove(punishmentId);
            logger.warning("[stat-wipe] Failed to acknowledge for " + punishmentId + ": " + disconnected.getMessage());
        }
    }
}
