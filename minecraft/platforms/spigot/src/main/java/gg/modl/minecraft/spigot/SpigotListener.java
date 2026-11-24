package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.LoginCache;
import gg.modl.minecraft.core.login.LoginAttempt;
import gg.modl.minecraft.core.login.LoginFlow;
import gg.modl.minecraft.core.login.LoginPipeline;
import gg.modl.minecraft.core.login.LoginRefusalLog;
import gg.modl.minecraft.core.login.LoginService;
import gg.modl.minecraft.core.login.PreLoginOutcome;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.util.Futures;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class SpigotListener implements Listener {
    private static final long RESULT_MARGIN_SECONDS = 2;

    private final SpigotPlatform platform;
    private final Cache cache;
    private final ChatMessageCache chatMessageCache;
    private final LoginPipeline loginPipeline;
    private final LoginFlow loginFlow;
    private final LoginRefusalPrediction refusals;

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED
                || refusals.refuses(event.getUniqueId(), event.getAddress())) {
            loginPipeline.getLoginCache().storePreLoginOutcome(event.getUniqueId(), PreLoginOutcome.skipped());
            return;
        }
        LoginAttempt attempt = new LoginAttempt(event.getUniqueId(), event.getName(),
                event.getAddress().getHostAddress(), platform.getServerName(), platform.preLoginTexture(event));
        LoginService.LoginResult result = awaitResult(attempt);
        loginPipeline.getLoginCache().storePreLoginOutcome(event.getUniqueId(), PreLoginOutcome.checked(result));
        String denialMessage = loginPipeline.getLoginService().denialMessage(result);
        if (denialMessage == null) return;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Colors.get(denialMessage));
        LoginRefusalLog.refused(platform.getLogger(), event.getName(), denialMessage);
    }

    private LoginService.LoginResult awaitResult(LoginAttempt attempt) {
        try {
            return loginFlow.begin(attempt).get(loginFlow.timeoutSeconds() + RESULT_MARGIN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return loginPipeline.getLoginService().handleLoginError(interrupted);
        } catch (Exception failure) {
            platform.getLogger().warning("Login check for " + attempt.getUsername() + " failed: " + failure.getMessage());
            return loginPipeline.getLoginService().handleLoginError(failure);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        loginPipeline.getPlayerSessionService().handlePlayerJoin(uuid, event.getPlayer().getName());
        cacheSkinTexture(uuid);
        chatMessageCache.updatePlayerServer(platform.getServerName(), uuid.toString());

        LoginCache.CachedLoginResult cachedResult = loginPipeline.getLoginCache().getCachedLoginResult(uuid);
        if (cachedResult != null) loginPipeline.getLoginService().cacheLoginData(uuid, cachedResult.getResponse());
    }

    private void cacheSkinTexture(UUID uuid) {
        Futures.whenSucceeded(loginFlow.textureAtJoin(uuid, platform.getPlayerSkinTexture(uuid)), texture -> {
            if (texture != null) cache.cacheSkinTexture(uuid, texture);
        }, platform.getLogger());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        loginPipeline.getPlayerSessionService().handlePlayerDisconnect(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

}
