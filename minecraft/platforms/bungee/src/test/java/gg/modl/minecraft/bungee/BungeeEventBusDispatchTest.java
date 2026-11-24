package gg.modl.minecraft.bungee;

import gg.modl.minecraft.core.AsyncCommandExecutor;
import gg.modl.minecraft.core.boot.LoginGate;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.LoginCache;
import gg.modl.minecraft.core.chat.ChatService;
import gg.modl.minecraft.core.chat.CommandInterceptService;
import gg.modl.minecraft.core.login.LoginFlow;
import gg.modl.minecraft.core.login.LoginPipeline;
import gg.modl.minecraft.core.login.LoginService;
import gg.modl.minecraft.core.service.ChatCommandLogService;
import gg.modl.minecraft.core.session.PlayerSessionService;
import gg.modl.minecraft.core.session.ServerSwitchService;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventBus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BungeeEventBusDispatchTest {
    private static final UUID PLAYER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private final EventBus eventBus = new EventBus();
    private final ProxiedPlayer player = mock(ProxiedPlayer.class);

    BungeeEventBusDispatchTest() {
        when(player.getUniqueId()).thenReturn(PLAYER);
        when(player.getName()).thenReturn("Steve");
    }

    @Test
    void theProxyEventBusReachesTheLoginGate() {
        LoginGate gate = mock(LoginGate.class);
        when(gate.isOpen()).thenReturn(false);
        when(gate.deniedMessage()).thenReturn("modl is starting");
        eventBus.register(new BungeeLoginGateListener(gate));
        PreLoginEvent login = new PreLoginEvent(mock(PendingConnection.class), (result, error) -> { });

        eventBus.post(login);

        assertTrue(login.isCancelled());
    }

    @Test
    void theProxyEventBusReachesTheChatListener() {
        ChatService chatService = mock(ChatService.class);
        eventBus.register(new BungeeChatListener(chatService, mock(CommandInterceptService.class),
                mock(ChatCommandLogService.class), mock(AsyncCommandExecutor.class), mock(ProxyServer.class)));

        eventBus.post(new ChatEvent(player, mock(Connection.class), "hello"));

        verify(chatService).handleChat(eq(PLAYER), eq("Steve"), eq("hello"), eq("unknown"), eq(false), any());
    }

    @Test
    void theProxyEventBusReachesThePlayerListener() {
        PlayerSessionService sessions = mock(PlayerSessionService.class);
        LoginPipeline pipeline = new LoginPipeline(mock(LoginService.class), mock(LoginCache.class), sessions);
        eventBus.register(new BungeeListener(null, mock(Cache.class), mock(Plugin.class), pipeline,
                mock(LoginFlow.class), mock(ServerSwitchService.class)));

        eventBus.post(new PlayerDisconnectEvent(player));

        verify(sessions).handlePlayerDisconnect(PLAYER, "Steve");
    }
}
