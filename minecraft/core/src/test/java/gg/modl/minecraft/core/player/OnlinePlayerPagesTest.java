package gg.modl.minecraft.core.player;

import gg.modl.minecraft.api.http.response.OnlinePlayersResponse;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OnlinePlayerPagesTest {
    @Test
    void followsTheCursorUntilTheLastPage() {
        List<String> requested = new ArrayList<>();
        FakeModlHttpClient client = new FakeModlHttpClient() {
            @Override
            public CompletableFuture<OnlinePlayersResponse> getOnlinePlayers(String cursor) {
                requested.add(String.valueOf(cursor));
                if (cursor == null) return page("next-1", player("a"), player("b"));
                if ("next-1".equals(cursor)) return page("next-2", player("c"));
                return page(null, player("d"));
            }
        };

        List<OnlinePlayersResponse.OnlinePlayer> players = OnlinePlayerPages.all(client).join();

        assertEquals(4, players.size());
        assertEquals(Arrays.asList("null", "next-1", "next-2"), requested);
    }

    @Test
    void stopsWhenTheServerRepeatsACursorOrPagesRunOut() {
        List<String> requested = new ArrayList<>();
        FakeModlHttpClient client = new FakeModlHttpClient() {
            @Override
            public CompletableFuture<OnlinePlayersResponse> getOnlinePlayers(String cursor) {
                requested.add(String.valueOf(cursor));
                return page("same", player("x"));
            }
        };

        assertEquals(2, OnlinePlayerPages.all(client).join().size());
        assertEquals(Arrays.asList("null", "same"), requested);
    }

    @Test
    void anUnboundedServerIsCutOffAfterTheMaximumPages() {
        FakeModlHttpClient client = new FakeModlHttpClient() {
            private int served;

            @Override
            public CompletableFuture<OnlinePlayersResponse> getOnlinePlayers(String cursor) {
                served++;
                return page("cursor-" + served, player("p" + served));
            }
        };

        assertEquals(OnlinePlayerPages.MAX_PAGES, OnlinePlayerPages.all(client).join().size());
    }

    @Test
    void theCursorIsSentAsAnEncodedQueryParameter() {
        assertEquals("/minecraft/players/online", OnlinePlayerPages.path(null));
        assertEquals("/minecraft/players/online?cursor=a%2Bb+c", OnlinePlayerPages.path("a+b c"));
    }

    private static CompletableFuture<OnlinePlayersResponse> page(String next, OnlinePlayersResponse.OnlinePlayer... players) {
        return CompletableFuture.completedFuture(new OnlinePlayersResponse(Arrays.asList(players), 200, next));
    }

    private static OnlinePlayersResponse.OnlinePlayer player(String name) {
        return new OnlinePlayersResponse.OnlinePlayer(name, name, null, 0L);
    }
}
