package gg.modl.minecraft.spigot.boot;

import gg.modl.minecraft.core.connection.ProxyForwarding;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.spigot.boot.api.LegacyPaperSpigotApi;
import gg.modl.minecraft.spigot.boot.api.PaperLikeSpigotApi;
import gg.modl.minecraft.spigot.boot.api.SpigotOnlyApi;
import gg.modl.minecraft.spigot.boot.impl.PaperLikeServer;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpigotProxyForwardingTest {
    private final PluginLogger logger = PluginLogger.fromJul(Logger.getLogger("spigot-proxy-forwarding-test"));

    @Test
    void readsVelocityForwardingThroughTheApiTypeOfANonPublicImplementation() {
        assertEquals(true, SpigotProxyForwarding.velocityForwardingEnabled(
                PaperLikeServer.spigotWithVelocityForwarding(true), PaperLikeSpigotApi.class, logger));
        assertEquals(false, SpigotProxyForwarding.velocityForwardingEnabled(
                PaperLikeServer.spigotWithVelocityForwarding(false), PaperLikeSpigotApi.class, logger));
    }

    @Test
    void aServerApiWithoutPaperSettingsMeansNoVelocityForwarding() {
        assertEquals(false, SpigotProxyForwarding.velocityForwardingEnabled(new Object(), Object.class, logger));
    }

    @Test
    void anUnreadablePaperConfigFallsBackToTheConfigFiles() {
        assertNull(SpigotProxyForwarding.velocityForwardingEnabled(new PaperLikeSpigotApi(), PaperLikeSpigotApi.class, logger));
    }

    @Test
    void paperSpigot188ReadsSpigotYmlThroughGetSpigotConfigBecauseItsGetConfigIsBukkitYml() {
        assertEquals(ProxyForwarding.BUNGEECORD, SpigotProxyForwarding.fromServerApi(
                PaperLikeServer.paperSpigot188(true), LegacyPaperSpigotApi.class, logger));
        assertEquals(ProxyForwarding.NONE, SpigotProxyForwarding.fromServerApi(
                PaperLikeServer.paperSpigot188(false), LegacyPaperSpigotApi.class, logger));
    }

    @Test
    void spigotReadsSpigotYmlThroughGetConfig() {
        assertEquals(ProxyForwarding.BUNGEECORD, SpigotProxyForwarding.fromServerApi(
                PaperLikeServer.spigot(true), SpigotOnlyApi.class, logger));
    }

    @Test
    void modernPaperPrefersVelocityModernForwarding() {
        assertEquals(ProxyForwarding.VELOCITY, SpigotProxyForwarding.fromServerApi(
                PaperLikeServer.paper(true, false), PaperLikeSpigotApi.class, logger));
        assertEquals(ProxyForwarding.BUNGEECORD, SpigotProxyForwarding.fromServerApi(
                PaperLikeServer.paper(false, true), PaperLikeSpigotApi.class, logger));
    }

    @Test
    void aSpigotYmlThatIsNotLoadedYetFallsBackToTheConfigFiles() {
        assertNull(SpigotProxyForwarding.fromServerApi(
                PaperLikeServer.paperSpigot188BeforeSpigotYmlIsLoaded(), LegacyPaperSpigotApi.class, logger));
    }
}
