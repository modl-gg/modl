package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.item.CirrusItemType;
import dev.simplix.cirrus.text.CirrusChatElement;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.IPAddress;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.integration.mojang.MojangProfiles;
import gg.modl.minecraft.core.integration.mojang.WebPlayer;
import gg.modl.minecraft.core.util.SharedExecutor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.function.Function;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class PlayerHeadItemBuilder {
    private static final Set<UUID> PENDING_TEXTURE_LOOKUPS = ConcurrentHashMap.newKeySet();
    private static final SharedExecutor<ExecutorService> TEXTURE_LOOKUP_SCHEDULER = new SharedExecutor<>(() ->
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "modl-head-texture-lookup");
                thread.setDaemon(true);
                return thread;
            }));
    private final Function<UUID, CompletableFuture<WebPlayer>> textureLookup;

    public PlayerHeadItemBuilder() {
        this(uuid -> MojangProfiles.client().get(uuid));
    }

    public PlayerHeadItemBuilder(Function<UUID, CompletableFuture<WebPlayer>> textureLookup) {
        this.textureLookup = textureLookup;
    }

    public CirrusItem create(Platform platform, Account targetAccount, String targetName, UUID targetUuid) {
        LocaleManager locale = PluginServices.locale();
        String unknown = locale.getUnknownWord();
        String notAvailable = locale.getMessage("general.not_available");
        List<String> lore = new ArrayList<>();

        String firstLogin = unknown;
        if (!targetAccount.getUsernames().isEmpty()) {
            Instant earliest = targetAccount.getUsernames().stream()
                    .map(Account.Username::getDate)
                    .filter(Objects::nonNull)
                    .min(Instant::compareTo)
                    .orElse(null);
            if (earliest != null) firstLogin = MenuItems.formatDate(earliest);
        }

        CachedProfile targetProfile = PluginServices.cache().getPlayerProfile(targetUuid);
        boolean isOnline = targetProfile != null;
        boolean isBanned = targetAccount.getPunishments().stream()
                .anyMatch(p -> p.isActive() && p.isBanType());
        boolean isMuted = targetAccount.getPunishments().stream()
                .anyMatch(p -> p.isActive() && p.isMuteType());

        boolean realIpLogged = !targetAccount.getIpList().isEmpty();

        String knownServer = null;
        if (isOnline) knownServer = platform.getPlayerServer(targetUuid);
        else {
            Object lastServer = targetAccount.getData().get("lastServer");
            if (lastServer instanceof String) knownServer = (String) lastServer;
        }
        String server = knownServer != null ? knownServer : unknown;
        String presence = Presence.describe(locale, isOnline, targetProfile != null ? targetProfile.getSessionDuration() : 0L,
                Presence.latestUsernameDate(targetAccount), knownServer);

        String location = unknown;
        if (!targetAccount.getIpList().isEmpty()) {
            IPAddress latestIp = targetAccount.getIpList().get(targetAccount.getIpList().size() - 1);
            location = location(latestIp, unknown);
        }

        String playtime = notAvailable;
        Object playtimeObj = targetAccount.getData().get("totalPlaytimeSeconds");
        long totalSeconds = 0;
        if (playtimeObj instanceof Number) totalSeconds = ((Number) playtimeObj).longValue();
        if (totalSeconds > 0) playtime = MenuItems.formatDuration(TimeUnit.SECONDS.toMillis(totalSeconds));

        Map<String, String> vars = new HashMap<>();
        vars.put("player_name", targetName);
        vars.put("uuid", targetUuid.toString());
        vars.put("first_login", firstLogin);
        vars.put("is_online", (isOnline ? "&a" : "&c") + locale.getYesNoWord(isOnline));
        vars.put("presence", presence);
        vars.put("playtime", playtime);
        vars.put("real_ip_logged", (realIpLogged ? "&a" : "&c") + locale.getYesNoWord(realIpLogged));
        vars.put("is_banned", (isBanned ? "&c" : "&a") + locale.getYesNoWord(isBanned));
        vars.put("is_muted", (isMuted ? "&c" : "&a") + locale.getYesNoWord(isMuted));
        vars.put("server", server);
        vars.put("location", location);

        List<String> loreLinesRaw = locale.getMessageList("menus.player_head.lore");
        lore.addAll(renderLoreLines(loreLinesRaw, vars, unknown));

        String title = locale.getMessage("menus.player_head.title", mapOf("player_name", targetName));

        CirrusItem headItem = CirrusItem.of(
                CirrusItemType.PLAYER_HEAD,
                CirrusChatElement.ofLegacyText(title),
                MenuItems.lore(lore)
        );

        if (PluginServices.cache() != null) {
            Cache cache = PluginServices.cache();
            String cachedTexture = cache.getSkinTexture(targetUuid);
            if (cachedTexture == null) {
                requestTextureLookup(cache, targetUuid);
            }
            if (cachedTexture != null) headItem = headItem.texture(cachedTexture);
        }

        return headItem;
    }

    private void requestTextureLookup(Cache cache, UUID targetUuid) {
        if (!PENDING_TEXTURE_LOOKUPS.add(targetUuid)) return;
        try {
            CompletableFuture.completedFuture(targetUuid)
                    .thenComposeAsync(uuid -> {
                        CompletableFuture<WebPlayer> textureFuture = textureLookup.apply(uuid);
                        return textureFuture != null ? textureFuture : CompletableFuture.completedFuture(null);
                    }, TEXTURE_LOOKUP_SCHEDULER.get())
                    .thenAccept(webPlayer -> {
                        if (webPlayer != null && webPlayer.isValid() && webPlayer.getTextureValue() != null) {
                            cache.cacheSkinTexture(targetUuid, webPlayer.getTextureValue());
                        }
                        PENDING_TEXTURE_LOOKUPS.remove(targetUuid);
                    })
                    .exceptionally(unavailable -> {
                        PENDING_TEXTURE_LOOKUPS.remove(targetUuid);
                        return null;
                    });
        } catch (Exception e) {
            PENDING_TEXTURE_LOOKUPS.remove(targetUuid);
        }
    }

    public static void shutdown() {
        TEXTURE_LOOKUP_SCHEDULER.shutdownNow();
    }

    static String location(IPAddress address, String unknown) {
        Set<String> parts = new LinkedHashSet<>();
        for (String part : new String[] {address.getRegion(), address.getCountry()}) {
            if (part != null && !part.trim().isEmpty()) parts.add(part.trim());
        }
        return parts.isEmpty() ? unknown : String.join(", ", parts);
    }

    private static String displayValue(@Nullable String value, String fallback) {
        return value != null && !value.trim().isEmpty() ? value : fallback;
    }

    static List<String> renderLoreLines(List<String> loreLinesRaw, Map<String, String> vars, String missingValue) {
        List<String> lore = new ArrayList<>();
        for (String line : loreLinesRaw) {
            String processed = line;
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                processed = processed.replace("{" + entry.getKey() + "}", displayValue(entry.getValue(), missingValue));
            }
            lore.add(processed);
        }
        return lore;
    }
}
