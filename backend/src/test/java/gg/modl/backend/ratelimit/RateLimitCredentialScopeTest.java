package gg.modl.backend.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig.RateLimitTier;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.serverkey.ServerKeyScope;
import io.github.bucket4j.Bucket;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RateLimitCredentialScopeTest {
    private static final String LOOPBACK = "127.0.0.1";
    private static final MinecraftCredential LOBBY = new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "lobby-key", "lobby");
    private static final MinecraftCredential SURVIVAL = new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "survival-key", "survival");

    private final RateLimitConfig config = new RateLimitConfig(new BucketPool());

    @Test
    void pluginTiersAreLimitedPerCredential() {
        assertTrue(RateLimitTier.MINECRAFT_LOGIN.isCredentialScoped());
        assertTrue(RateLimitTier.MINECRAFT_STANDARD.isCredentialScoped());
        assertTrue(RateLimitTier.MINECRAFT_PANEL_LINK.isCredentialScoped());
        assertFalse(RateLimitTier.MINECRAFT_STANDARD.isAccountScoped());
    }

    @Test
    void theSizesCoverAJoinBurstOfANetwork() {
        assertEquals(10_000, RateLimitTier.MINECRAFT_LOGIN.getCapacity());
        assertEquals(6_000, RateLimitTier.MINECRAFT_STANDARD.getCapacity());
        assertEquals(30_000, RateLimitTier.MINECRAFT_CLIENT_CEILING.getCapacity());
    }

    @Test
    void serversSharingOneAddressDoNotShareABudget() {
        Bucket lobby = config.credentialBucket(RateLimitTier.MINECRAFT_STANDARD, LOBBY).orElseThrow();
        Bucket survival = config.credentialBucket(RateLimitTier.MINECRAFT_STANDARD, SURVIVAL).orElseThrow();

        assertTrue(lobby.tryConsume(RateLimitTier.MINECRAFT_STANDARD.getCapacity()));

        assertFalse(lobby.tryConsume(1));
        assertTrue(survival.tryConsume(1));
    }

    @Test
    void theEmbeddedServersOwnKeyIsNeverStarvedByTheInstanceKey() {
        Bucket instance = config.credentialBucket(RateLimitTier.MINECRAFT_STANDARD, MinecraftCredential.instanceKey()).orElseThrow();
        Bucket internal = config.credentialBucket(RateLimitTier.MINECRAFT_STANDARD, MinecraftCredential.internalKey()).orElseThrow();

        assertTrue(instance.tryConsume(RateLimitTier.MINECRAFT_STANDARD.getCapacity()));

        assertNotSame(instance, internal);
        assertTrue(internal.tryConsume(1));
    }

    @Test
    void beforeAuthenticationEveryPluginTierSharesOneGenerousCeilingPerAddress() {
        Bucket ceiling = config.resolveBucket(LOOPBACK, RateLimitTier.MINECRAFT_CLIENT_CEILING);

        assertSame(ceiling, config.clientBucket(RateLimitTier.MINECRAFT_LOGIN, LOOPBACK));
        assertSame(ceiling, config.clientBucket(RateLimitTier.MINECRAFT_STANDARD, LOOPBACK));
        assertSame(ceiling, config.clientBucket(RateLimitTier.MINECRAFT_PANEL_LINK, LOOPBACK));
    }

    @Test
    void noCredentialMeansNoCredentialBucket() {
        assertEquals(Optional.empty(), config.credentialBucket(RateLimitTier.MINECRAFT_STANDARD, null));
        assertEquals(Optional.empty(), config.credentialBucket(RateLimitTier.PANEL_STANDARD, LOBBY));
    }
}
