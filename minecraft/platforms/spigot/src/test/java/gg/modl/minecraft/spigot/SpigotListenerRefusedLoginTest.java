package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.cache.LoginCache;
import gg.modl.minecraft.core.login.LoginFlow;
import gg.modl.minecraft.core.login.LoginPipeline;
import gg.modl.minecraft.core.login.LoginService;
import gg.modl.minecraft.core.login.PreLoginOutcome;
import gg.modl.minecraft.core.util.PluginLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpigotListenerRefusedLoginTest {
    private static final UUID PLAYER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final String UNCHECKED = "Your login could not be checked against this server's bans.";
    private static final String UNVERIFIABLE = "Unable to verify ban status.";
    private static final String BANNED = "\u00a7cYou are banned from this server.";

    private final LoginFlow loginFlow = mock(LoginFlow.class);
    private final LoginCache loginCache = new LoginCache();
    private final LoginService loginService = mock(LoginService.class);
    private final LoginPipeline pipeline = new LoginPipeline(loginService, loginCache, null);
    private final SpigotPlatform platform = mock(SpigotPlatform.class);
    private boolean vanillaRefuses;
    private final SpigotListener listener = new SpigotListener(platform, null, null, pipeline, loginFlow,
            (uuid, address) -> vanillaRefuses);
    private final LegacyLoginValidation loginValidation =
            new LegacyLoginValidation(new LoginAdmission(mock(PluginLogger.class), pipeline));

    SpigotListenerRefusedLoginTest() {
        when(platform.getLogger()).thenReturn(mock(PluginLogger.class));
        when(loginService.uncheckedLoginMessage()).thenReturn(UNCHECKED);
        when(loginService.unverifiableBanStatusMessage()).thenReturn(UNVERIFIABLE);
    }

    @AfterEach
    void stop() {
        loginCache.shutdown();
    }

    @Test
    void aLoginAnotherPluginRefusedGetsNoLookupNoRowAndNoLink() throws Exception {
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent("Bot123", InetAddress.getByName("203.0.113.7"), PLAYER);
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "You are not whitelisted");

        listener.onAsyncPlayerPreLogin(event);

        verify(loginFlow, never()).begin(any());
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, event.getLoginResult());
        assertTrue(loginCache.takePreLoginOutcome(PLAYER).isSkipped());
    }

    @Test
    void aLoginTheServerRefusesLaterKeepsItsRefusalAndLeavesNoPendingResult() throws Exception {
        loginCache.storePreLoginOutcome(PLAYER, PreLoginOutcome.checked(new LoginService.LoginResult.Allowed(null)));
        PlayerLoginEvent event = loginEvent();
        event.disallow(PlayerLoginEvent.Result.KICK_FULL, "The server is full");

        loginValidation.onPlayerLogin(event);

        assertEquals(PlayerLoginEvent.Result.KICK_FULL, event.getResult());
        assertNull(loginCache.takePreLoginOutcome(PLAYER));
    }

    @Test
    void aVanillaRefusalThatAnotherPluginOverridesIsDeniedWithItsOwnMessage() throws Exception {
        vanillaRefuses = true;
        listener.onAsyncPlayerPreLogin(new AsyncPlayerPreLoginEvent("Steve", InetAddress.getByName("203.0.113.7"), PLAYER));
        PlayerLoginEvent event = loginEvent();

        loginValidation.onPlayerLogin(event);

        verify(loginFlow, never()).begin(any());
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, event.getResult());
        assertEquals(UNCHECKED, event.getKickMessage());
    }

    @Test
    void aLoginWithoutAnyPreLoginStillFailsClosed() throws Exception {
        PlayerLoginEvent event = loginEvent();

        loginValidation.onPlayerLogin(event);

        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, event.getResult());
        assertEquals(UNVERIFIABLE, event.getKickMessage());
    }

    @Test
    void aLoginTheCheckRefusedIsKickedAsBannedWithTheCheckMessage() throws Exception {
        LoginService.LoginResult banned = new LoginService.LoginResult.Denied(BANNED);
        when(loginService.denialMessage(banned)).thenReturn(BANNED);
        loginCache.storePreLoginOutcome(PLAYER, PreLoginOutcome.checked(banned));
        PlayerLoginEvent event = loginEvent();

        loginValidation.onPlayerLogin(event);

        assertEquals(PlayerLoginEvent.Result.KICK_BANNED, event.getResult());
        assertEquals(BANNED, event.getKickMessage());
    }

    private static PlayerLoginEvent loginEvent() throws Exception {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER);
        when(player.getName()).thenReturn("Steve");
        return new PlayerLoginEvent(player, "localhost", InetAddress.getByName("203.0.113.7"));
    }
}
