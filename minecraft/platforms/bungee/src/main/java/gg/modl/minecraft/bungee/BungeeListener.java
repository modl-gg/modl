package gg.modl.minecraft.bungee;

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
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

@RequiredArgsConstructor
public class BungeeListener implements Listener {
    private final BungeePlatform platform;
    private final Cache cache;
    private final Plugin plugin;
    private final LoginPipeline loginPipeline;
    private final LoginFlow loginFlow;
    private final ServerSwitchService serverSwitchService;

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(LoginEvent event) {
        if (event.isCancelled()) return;
        event.registerIntent(plugin);
        try {
            PendingConnection connection = event.getConnection();
            LoginAttempt attempt = new LoginAttempt(connection.getUniqueId(), connection.getName(),
                    extractIpAddress(connection.getSocketAddress()), platform.getServerName(),
                    platform.loginTexture(connection));
            Futures.whenComplete(loginFlow.begin(attempt), (result, failure) -> applyLoginResult(event, result, failure),
                    platform.getLogger());
        } catch (Throwable failure) {
            denyLogin(event, loginPipeline.getLoginService().unverifiableBanStatusMessage(), failure);
            event.completeIntent(plugin);
        }
    }

    private void applyLoginResult(LoginEvent event, LoginService.@Nullable LoginResult result,
                                  @Nullable Throwable failure) {
        try {
            if (failure != null || result == null) {
                denyLogin(event, loginPipeline.getLoginService().unverifiableBanStatusMessage(), failure);
                return;
            }
            String message = loginPipeline.getLoginService().denialMessage(result);
            if (message != null) denyLogin(event, message, null);
        } catch (Throwable applyFailure) {
            denyLogin(event, loginPipeline.getLoginService().unverifiableBanStatusMessage(), applyFailure);
        } finally {
            event.completeIntent(plugin);
        }
    }

    @SuppressWarnings("deprecation")
    private void denyLogin(LoginEvent event, String reason, @Nullable Throwable failure) {
        event.setCancelReason(platform.toKickComponent(reason));
        event.setCancelled(true);
        if (failure == null) {
            LoginRefusalLog.refused(platform.getLogger(), event.getConnection().getName(), reason);
        } else {
            platform.getLogger().warning("Login verification failed for " + event.getConnection().getName()
                    + " - denying login", failure);
        }
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        loginPipeline.getPlayerSessionService().handlePlayerJoin(uuid, event.getPlayer().getName());

        Futures.whenSucceeded(loginFlow.textureAtJoin(uuid, platform.getPlayerSkinTexture(uuid)), texture -> {
            if (texture != null) cache.cacheSkinTexture(uuid, texture);
        }, platform.getLogger());

        LoginCache.CachedLoginResult cachedResult = loginPipeline.getLoginCache().getCachedLoginResult(uuid);
        if (cachedResult != null) loginPipeline.getLoginService().cacheLoginData(uuid, cachedResult.getResponse());
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        loginPipeline.getPlayerSessionService().handlePlayerDisconnect(
                event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler
    public void onServerSwitch(ServerSwitchEvent event) {
        serverSwitchService.handleServerSwitch(
                event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                platform.getPlayerServer(event.getPlayer().getUniqueId()));
    }

    private String extractIpAddress(SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress) return ((InetSocketAddress) socketAddress).getAddress().getHostAddress();
        String addr = socketAddress.toString();
        if (addr.startsWith("/")) addr = addr.substring(1);
        if (addr.contains(":")) addr = addr.substring(0, addr.indexOf(":"));
        return addr;
    }
}
