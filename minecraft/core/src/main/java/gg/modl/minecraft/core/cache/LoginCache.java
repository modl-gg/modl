package gg.modl.minecraft.core.cache;

import gg.modl.minecraft.api.http.response.PlayerLoginResponse;
import gg.modl.minecraft.core.login.PreLoginOutcome;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class LoginCache {
    private static final PluginLogger LOGGER = PluginLogger.fromJul(Logger.getLogger(LoginCache.class.getName()));

    private static final int LOGIN_RESULT_EXPIRY_SECONDS = 30, PRE_LOGIN_RESULT_EXPIRY_SECONDS = 60;

    private final ConcurrentHashMap<UUID, CachedLoginResult> loginResultCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PreLoginResult> preLoginResults = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    public LoginCache() {
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "modl-login-cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        Scheduling.atFixedRate(cleanupExecutor, this::cleanup, 30, 30, TimeUnit.SECONDS, LOGGER);
    }

    public @Nullable CachedLoginResult getCachedLoginResult(UUID playerUuid) {
        CachedLoginResult cached = loginResultCache.get(playerUuid);
        if (cached != null && !cached.isExpired()) return cached;
        return null;
    }

    public void cacheLoginResult(UUID playerUuid, PlayerLoginResponse response, @Nullable String skinHash) {
        if (response != null && response.hasActiveBan()) {
            loginResultCache.remove(playerUuid);
            return;
        }
        loginResultCache.put(playerUuid, new CachedLoginResult(response, skinHash, Instant.now()));
    }

    public void invalidateLoginResult(UUID playerUuid) {
        loginResultCache.remove(playerUuid);
    }

    public void storePreLoginOutcome(UUID playerUuid, PreLoginOutcome outcome) {
        preLoginResults.put(playerUuid, new PreLoginResult(outcome, Instant.now()));
    }

    public @Nullable PreLoginOutcome takePreLoginOutcome(UUID playerUuid) {
        PreLoginResult stored = preLoginResults.remove(playerUuid);
        return stored == null ? null : stored.getOutcome();
    }

    private void cleanup() {
        loginResultCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        Instant cutoff = Instant.now().minusSeconds(PRE_LOGIN_RESULT_EXPIRY_SECONDS);
        preLoginResults.entrySet().removeIf(entry -> entry.getValue().getTimestamp().isBefore(cutoff));
    }

    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) cleanupExecutor.shutdownNow();
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        loginResultCache.clear();
        preLoginResults.clear();
    }

    @Getter @RequiredArgsConstructor
    public static class CachedLoginResult {
        private final PlayerLoginResponse response;
        private final @Nullable String skinHash;
        private final Instant timestamp;

        public boolean isExpired() {
            return Instant.now().isAfter(timestamp.plusSeconds(LOGIN_RESULT_EXPIRY_SECONDS));
        }
    }

    @Getter @RequiredArgsConstructor
    private static final class PreLoginResult {
        private final PreLoginOutcome outcome;
        private final Instant timestamp;
    }
}
