package gg.modl.minecraft.core.login;

import gg.modl.minecraft.core.integration.mojang.WebPlayer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoginSkinsTest {
    private static final UUID PLAYER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    @Test
    void theTextureFromTheLoginProfileIsUsedWithoutAnyLookup() {
        AtomicInteger lookups = new AtomicInteger();
        LoginSkins skins = new LoginSkins(uuid -> {
            lookups.incrementAndGet();
            return new CompletableFuture<>();
        });

        assertEquals("abc", skins.skinHashAtLogin(PLAYER, texture("abc")));
        assertEquals(0, lookups.get());
    }

    @Test
    void aSlowLookupNeverHoldsTheLoginAndItsAnswerIsUsedNextTime() {
        CompletableFuture<WebPlayer> pending = new CompletableFuture<>();
        LoginSkins skins = new LoginSkins(uuid -> pending);

        assertNull(skins.skinHashAtLogin(PLAYER, null));

        pending.complete(new WebPlayer("Steve", PLAYER, "def", null, true));
        assertEquals("def", skins.skinHashAtLogin(PLAYER, null));
    }

    @Test
    void aFailedLookupLeavesTheSkinUnknown() {
        CompletableFuture<WebPlayer> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("blocked"));
        LoginSkins skins = new LoginSkins(uuid -> failed);

        assertNull(skins.skinHashAtLogin(PLAYER, null));
        assertNull(skins.skinHashAtLogin(PLAYER, null));
    }

    @Test
    void theSkinSeenAtJoinIsRememberedForTheNextLogin() {
        LoginSkins skins = new LoginSkins(uuid -> new CompletableFuture<>());

        assertEquals(texture("ghi"), skins.textureAtJoin(PLAYER, texture("ghi")).join());

        assertEquals("ghi", skins.skinHashAtLogin(PLAYER, null));
    }

    @Test
    void aJoinWithoutANativeTextureReusesTheLoginLookupInsteadOfASecondOne() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        CompletableFuture<WebPlayer> profile = new CompletableFuture<>();
        LoginSkins skins = new LoginSkins(uuid -> {
            lookups.incrementAndGet();
            return profile;
        });

        skins.skinHashAtLogin(PLAYER, null);
        CompletableFuture<String> atJoin = skins.textureAtJoin(PLAYER, null);
        profile.complete(new WebPlayer("Steve", PLAYER, "def", "texture-of-def", true));

        assertEquals("texture-of-def", atJoin.get(1, TimeUnit.SECONDS));
        assertEquals("texture-of-def", skins.textureAtJoin(PLAYER, null).get(1, TimeUnit.SECONDS));
        assertEquals(1, lookups.get());
    }

    @Test
    void aJoinNeverStartsALookupOfItsOwn() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        LoginSkins skins = new LoginSkins(uuid -> {
            lookups.incrementAndGet();
            return new CompletableFuture<>();
        });

        assertNull(skins.textureAtJoin(PLAYER, null).get(1, TimeUnit.SECONDS));
        assertEquals(0, lookups.get());
    }

    private static String texture(String skinId) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + skinId + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
