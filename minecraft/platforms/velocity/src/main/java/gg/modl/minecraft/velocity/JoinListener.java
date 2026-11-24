package gg.modl.minecraft.velocity;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.LoginCache;
import gg.modl.minecraft.core.login.LoginAttempt;
import gg.modl.minecraft.core.login.LoginFlow;
import gg.modl.minecraft.core.login.LoginPipeline;
import gg.modl.minecraft.core.login.LoginRefusalLog;
import gg.modl.minecraft.core.login.LoginService;
import gg.modl.minecraft.core.session.ServerSwitchService;
import gg.modl.minecraft.core.util.Futures;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;

@RequiredArgsConstructor
public class JoinListener {
    private final Cache cache;
    private final Logger logger;
    private final VelocityPlatform platform;
    private final LoginPipeline loginPipeline;
    private final LoginFlow loginFlow;
    private final ServerSwitchService serverSwitchService;
    private final boolean debugMode;

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.LATE)
    public @Nullable EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) return null;
        try {
            Player player = event.getPlayer();
            LoginAttempt attempt = new LoginAttempt(player.getUniqueId(), player.getUsername(),
                    player.getRemoteAddress().getAddress().getHostAddress(), platform.getServerName(),
                    VelocityPlatform.texturesOf(player));
            return EventTask.resumeWhenComplete(loginFlow.begin(attempt)
                    .thenAccept(result -> applyLoginResult(event, result)));
        } catch (Throwable failure) {
            denyLogin(event, loginPipeline.getLoginService().unverifiableBanStatusMessage(), failure);
            return null;
        }
    }

    private void applyLoginResult(LoginEvent event, LoginService.LoginResult result) {
        try {
            String message = loginPipeline.getLoginService().denialMessage(result);
            if (message != null) denyLogin(event, message, null);
            else if (debugMode) logger.info("Allowed login for {}", event.getPlayer().getUsername());
        } catch (Throwable failure) {
            denyLogin(event, loginPipeline.getLoginService().unverifiableBanStatusMessage(), failure);
        }
    }

    private void denyLogin(LoginEvent event, String message, @Nullable Throwable failure) {
        event.setResult(ResultedEvent.ComponentResult.denied(platform.toKickComponent(message)));
        if (failure == null) LoginRefusalLog.refused(platform.getLogger(), event.getPlayer().getUsername(), message);
        else logger.error("Login verification failed for {} - denying login", event.getPlayer().getUsername(), failure);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        loginPipeline.getPlayerSessionService().handlePlayerJoin(uuid, event.getPlayer().getUsername());

        Futures.whenSucceeded(loginFlow.textureAtJoin(uuid, platform.getPlayerSkinTexture(uuid)), texture -> {
            if (texture != null) cache.cacheSkinTexture(uuid, texture);
        }, platform.getLogger());

        LoginCache.CachedLoginResult cachedResult = loginPipeline.getLoginCache().getCachedLoginResult(uuid);
        if (cachedResult != null) loginPipeline.getLoginService().cacheLoginData(uuid, cachedResult.getResponse());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        loginPipeline.getPlayerSessionService().handlePlayerDisconnect(
                event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        serverSwitchService.handleServerSwitch(
                event.getPlayer().getUniqueId(), event.getPlayer().getUsername(),
                event.getServer().getServerInfo().getName());
    }

}
