package gg.modl.minecraft.core.player;

import gg.modl.minecraft.api.http.PlayerApi;
import gg.modl.minecraft.api.http.response.OnlinePlayersResponse;
import gg.modl.minecraft.core.util.UrlEncoding;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class OnlinePlayerPages {
    static final int MAX_PAGES = 200;
    private static final String ONLINE_PATH = "/minecraft/players/online";

    private OnlinePlayerPages() {
    }

    public static String path(@Nullable String cursor) {
        if (cursor == null || cursor.isEmpty()) return ONLINE_PATH;
        return ONLINE_PATH + "?cursor=" + UrlEncoding.encode(cursor);
    }

    public static CompletableFuture<List<OnlinePlayersResponse.OnlinePlayer>> all(PlayerApi api) {
        return collect(api, null, new ArrayList<>(), 1);
    }

    private static CompletableFuture<List<OnlinePlayersResponse.OnlinePlayer>> collect(
            PlayerApi api, @Nullable String cursor, List<OnlinePlayersResponse.OnlinePlayer> collected, int page) {
        return api.getOnlinePlayers(cursor).thenCompose(response -> {
            if (!response.isSuccess() || response.getPlayers() == null) {
                return CompletableFuture.completedFuture(Collections.unmodifiableList(collected));
            }
            collected.addAll(response.getPlayers());
            String next = response.getNextCursor();
            if (next == null || next.isEmpty() || next.equals(cursor) || page >= MAX_PAGES) {
                return CompletableFuture.completedFuture(Collections.unmodifiableList(collected));
            }
            return collect(api, next, collected, page + 1);
        });
    }
}
