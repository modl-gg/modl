package gg.modl.minecraft.spigot;

import com.destroystokyo.paper.profile.PlayerProfile;
import gg.modl.minecraft.core.cache.LoginCache;
import gg.modl.minecraft.core.login.LoginPipeline;
import gg.modl.minecraft.core.login.LoginService;
import gg.modl.minecraft.core.login.PreLoginOutcome;
import gg.modl.minecraft.core.util.PluginLogger;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Server;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperLoginValidationTest {
    private static final UUID PLAYER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final String BANNED = "§cYou are banned from this server.\n§7Reason: §fgriefing";
    private static final String UNCHECKED = "Your login could not be checked against this server's bans.";
    private static final String UNVERIFIABLE = "Unable to verify ban status.";

    private final LoginCache loginCache = new LoginCache();
    private final LoginService loginService = mock(LoginService.class);
    private final PluginLogger logger = mock(PluginLogger.class);
    private final LoginAdmission admission = new LoginAdmission(logger, new LoginPipeline(loginService, loginCache, null));
    private final PaperLoginValidation validation = PaperLoginValidation.detect(admission, logger).orElseThrow(AssertionError::new);

    PaperLoginValidationTest() {
        when(loginService.uncheckedLoginMessage()).thenReturn(UNCHECKED);
        when(loginService.unverifiableBanStatusMessage()).thenReturn(UNVERIFIABLE);
    }

    @AfterEach
    void stop() {
        loginCache.shutdown();
    }

    @Test
    void aLoginRefusedByTheCheckIsKickedWithItsTextReadAsLegacySectionCodes() throws Exception {
        LoginService.LoginResult banned = new LoginService.LoginResult.Denied(BANNED);
        when(loginService.denialMessage(banned)).thenReturn(BANNED);
        loginCache.storePreLoginOutcome(PLAYER, PreLoginOutcome.checked(banned));
        PlayerConnectionValidateLoginEvent event = loginPhase(profile(PLAYER), null);

        validation.validate(event);

        assertEquals(LegacyComponentSerializer.legacySection().deserialize(BANNED), event.getKickMessage());
    }

    @Test
    void anAllowedLoginStaysAllowed() throws Exception {
        loginCache.storePreLoginOutcome(PLAYER, PreLoginOutcome.checked(new LoginService.LoginResult.Allowed(null)));
        PlayerConnectionValidateLoginEvent event = loginPhase(profile(PLAYER), null);

        validation.validate(event);

        assertTrue(event.isAllowed());
        assertNull(loginCache.takePreLoginOutcome(PLAYER));
    }

    @Test
    void aLoginTheServerRefusedKeepsItsRefusalAndLeavesNoPendingResult() throws Exception {
        loginCache.storePreLoginOutcome(PLAYER, PreLoginOutcome.checked(new LoginService.LoginResult.Allowed(null)));
        Component full = Component.text("The server is full");
        PlayerConnectionValidateLoginEvent event = loginPhase(profile(PLAYER), full);

        validation.validate(event);

        assertEquals(full, event.getKickMessage());
        assertNull(loginCache.takePreLoginOutcome(PLAYER));
    }

    @Test
    void aLoginWhoseBansWereNotCheckedIsDenied() throws Exception {
        loginCache.storePreLoginOutcome(PLAYER, PreLoginOutcome.skipped());
        PlayerConnectionValidateLoginEvent event = loginPhase(profile(PLAYER), null);

        validation.validate(event);

        assertEquals(legacy(UNCHECKED), event.getKickMessage());
    }

    @Test
    void aLoginWithoutAnyPreLoginStillFailsClosed() throws Exception {
        PlayerConnectionValidateLoginEvent event = loginPhase(profile(PLAYER), null);

        validation.validate(event);

        assertEquals(legacy(UNVERIFIABLE), event.getKickMessage());
    }

    @Test
    void aLoginWithoutAnAuthenticatedProfileFailsClosed() throws Exception {
        PlayerConnectionValidateLoginEvent event = loginPhase(null, null);

        validation.validate(event);

        assertEquals(legacy(UNVERIFIABLE), event.getKickMessage());
    }

    @Test
    void theEndOfConfigurationIsNotASecondLogin() throws Exception {
        PreLoginOutcome pending = PreLoginOutcome.checked(new LoginService.LoginResult.Allowed(null));
        loginCache.storePreLoginOutcome(PLAYER, pending);
        PlayerConnectionValidateLoginEvent event =
                new PlayerConnectionValidateLoginEvent(mock(PlayerConfigurationConnection.class), null);

        validation.validate(event);

        assertTrue(event.isAllowed());
        assertNotNull(loginCache.takePreLoginOutcome(PLAYER));
    }

    @Test
    void aLoginWhoseCheckThrowsFailsClosed() throws Exception {
        LoginService.LoginResult allowed = new LoginService.LoginResult.Allowed(null);
        when(loginService.denialMessage(allowed)).thenThrow(new IllegalStateException("broken"));
        loginCache.storePreLoginOutcome(PLAYER, PreLoginOutcome.checked(allowed));
        PlayerConnectionValidateLoginEvent event = loginPhase(profile(PLAYER), null);

        validation.validate(event);

        assertEquals(legacy(UNVERIFIABLE), event.getKickMessage());
    }

    @Test
    void itListensToTheValidateEventAtHighestPriorityAndValidatesThroughItsExecutor() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        ArgumentCaptor<EventExecutor> executor = ArgumentCaptor.forClass(EventExecutor.class);
        PlayerConnectionValidateLoginEvent event = loginPhase(profile(PLAYER), null);

        validation.registerWith(plugin);
        verify(pluginManager).registerEvent(eq(PlayerConnectionValidateLoginEvent.class), same(validation),
                eq(EventPriority.HIGHEST), executor.capture(), same(plugin));
        executor.getValue().execute(validation, event);

        assertEquals(legacy(UNVERIFIABLE), event.getKickMessage());
    }

    private static PlayerConnectionValidateLoginEvent loginPhase(PlayerProfile profile, Component refusal) {
        PlayerLoginConnection connection = mock(PlayerLoginConnection.class);
        when(connection.getAuthenticatedProfile()).thenReturn(profile);
        return new PlayerConnectionValidateLoginEvent(connection, refusal);
    }

    private static PlayerProfile profile(UUID id) {
        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.getId()).thenReturn(id);
        when(profile.getName()).thenReturn("Steve");
        return profile;
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }
}
