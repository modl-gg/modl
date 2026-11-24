package gg.modl.minecraft.core.cache;

import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.api.http.response.PlayerLoginResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoginCacheTest {
    private final LoginCache cache = new LoginCache();
    private final UUID player = UUID.randomUUID();

    @AfterEach
    void shutDown() {
        cache.shutdown();
    }

    @Test
    void aDeniedLoginIsCheckedAgainOnTheNextAttempt() {
        cache.cacheLoginResult(player, loginWith("BAN"), "bad-skin");

        assertNull(cache.getCachedLoginResult(player));
    }

    @Test
    void aBanReplacesAnEarlierAllowedResult() {
        cache.cacheLoginResult(player, loginWith("MUTE"), null);
        cache.cacheLoginResult(player, loginWith("BAN"), null);

        assertNull(cache.getCachedLoginResult(player));
    }

    @Test
    void anAllowedLoginIsStillCached() {
        cache.cacheLoginResult(player, loginWith("MUTE"), null);

        assertNotNull(cache.getCachedLoginResult(player));
    }

    private static PlayerLoginResponse loginWith(String category) {
        SimplePunishment punishment = new SimplePunishment(
                "Bad Skin", category, null, "Change your skin", "SKIN0001", null, null, null, true, 11, false);
        return new PlayerLoginResponse(Collections.singletonList(punishment), null, null, 200);
    }
}
