package gg.modl.minecraft.core.login;

import gg.modl.minecraft.core.integration.mojang.SkinTextures;
import gg.modl.minecraft.core.integration.mojang.WebPlayer;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;

public final class LoginSkins {
    static final int REMEMBERED_PLAYERS = 4096;

    private static final PluginLogger LOGGER = PluginLogger.fromJul(Logger.getLogger(LoginSkins.class.getName()));

    private final Function<UUID, CompletableFuture<WebPlayer>> profiles;
    private final Map<UUID, KnownSkin> remembered = new LinkedHashMap<UUID, KnownSkin>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, KnownSkin> eldest) {
            return size() > REMEMBERED_PLAYERS;
        }
    };
    private final Map<UUID, CompletableFuture<@Nullable KnownSkin>> pending = new HashMap<>();

    public LoginSkins(Function<UUID, CompletableFuture<WebPlayer>> profiles) {
        this.profiles = profiles;
    }

    public @Nullable String skinHashAtLogin(UUID uuid, @Nullable String nativeTexture) {
        KnownSkin observed = KnownSkin.ofTexture(nativeTexture);
        if (observed != null) {
            remember(uuid, observed);
            return observed.hash;
        }
        lookUp(uuid);
        synchronized (remembered) {
            KnownSkin known = remembered.get(uuid);
            return known == null ? null : known.hash;
        }
    }

    public CompletableFuture<@Nullable String> textureAtJoin(UUID uuid, @Nullable String nativeTexture) {
        KnownSkin observed = KnownSkin.ofTexture(nativeTexture);
        if (observed != null) {
            remember(uuid, observed);
            return CompletableFuture.completedFuture(nativeTexture);
        }
        synchronized (remembered) {
            CompletableFuture<@Nullable KnownSkin> inFlight = pending.get(uuid);
            if (inFlight != null) return inFlight.thenApply(KnownSkin::texture);
            return CompletableFuture.completedFuture(KnownSkin.texture(remembered.get(uuid)));
        }
    }

    private void lookUp(UUID uuid) {
        CompletableFuture<@Nullable KnownSkin> lookup;
        synchronized (remembered) {
            if (pending.containsKey(uuid)) return;
            lookup = new CompletableFuture<>();
            pending.put(uuid, lookup);
        }
        Futures.whenComplete(profiles.apply(uuid), (profile, failure) -> {
            KnownSkin found = failure == null ? KnownSkin.ofProfile(profile) : null;
            synchronized (remembered) {
                pending.remove(uuid);
                if (found != null) remembered.put(uuid, found);
            }
            lookup.complete(found);
        }, LOGGER);
    }

    private void remember(UUID uuid, KnownSkin skin) {
        synchronized (remembered) {
            remembered.put(uuid, skin);
        }
    }

    private static final class KnownSkin {
        private final String hash;
        private final @Nullable String texture;

        private KnownSkin(String hash, @Nullable String texture) {
            this.hash = hash;
            this.texture = texture;
        }

        static @Nullable KnownSkin ofTexture(@Nullable String texture) {
            String hash = SkinTextures.skinId(texture);
            return hash == null ? null : new KnownSkin(hash, texture);
        }

        static @Nullable KnownSkin ofProfile(@Nullable WebPlayer profile) {
            if (profile == null || !profile.isValid()) return null;
            String skin = profile.getSkin();
            return skin == null ? null : new KnownSkin(skin, profile.getTextureValue());
        }

        static @Nullable String texture(@Nullable KnownSkin skin) {
            return skin == null ? null : skin.texture;
        }
    }
}
