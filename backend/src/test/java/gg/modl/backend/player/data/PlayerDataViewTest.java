package gg.modl.backend.player.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayerDataViewTest {

    @Test
    void mutatingOneFieldPreservesUnknownAndUntouchedKeys() {
        Player player = Player.builder().build();
        player.data().setOnline(true);
        player.data().asMap().put(PlayerDataKeys.LAST_SERVER, "hub");
        player.data().asMap().put("legacyPluginField", "keep-me");

        player.data().setLastServer("lobby");

        Map<String, Object> stored = player.data().asMap();
        assertEquals(true, stored.get(PlayerDataKeys.IS_ONLINE));
        assertEquals("lobby", stored.get(PlayerDataKeys.LAST_SERVER));
        assertEquals("keep-me", stored.get("legacyPluginField"));
        assertEquals(3, stored.size());
    }

    @Test
    void anOnlinePlayersPlaytimeIncludesTheCurrentSession() {
        Instant now = Instant.parse("2026-11-23T06:40:00Z");
        Player player = Player.builder().build();
        player.data().setOnline(true);
        player.data().setLastLogin(now.minusSeconds(13));
        player.data().setTotalPlaytimeSeconds(4);

        assertEquals(17, player.data().playtimeSecondsAt(now));
        assertEquals(17L, player.data().asMapAt(now).get(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS));
        assertEquals(4L, player.data().asMap().get(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS));
    }

    @Test
    void anOfflinePlayersPlaytimeIsTheStoredTotal() {
        Instant now = Instant.parse("2026-11-23T06:40:00Z");
        Player player = Player.builder().build();
        player.data().setLastLogin(now.minusSeconds(600));
        player.data().setTotalPlaytimeSeconds(4);

        assertEquals(4, player.data().playtimeSecondsAt(now));
        assertSame(player.data().asMap(), player.data().asMapAt(now));
    }

    @Test
    void aClockBehindTheLoginAddsNoSession() {
        Instant now = Instant.parse("2026-11-23T06:40:00Z");
        Player player = Player.builder().build();
        player.data().setOnline(true);
        player.data().setLastLogin(now.plusSeconds(5));

        assertEquals(0, player.data().playtimeSecondsAt(now));
    }

    @Test
    void readsAreNullSafeWhenDataAbsent() {
        Player player = Player.builder().build();
        player.replaceData(null);

        assertFalse(player.data().isOnline());
        assertNull(player.data().lastLogin());
        assertNull(player.data().lastServer());
        assertNull(player.data().totalPlaytimeSeconds());
        assertTrue(player.data().linkedAccountUuids().isEmpty());
        assertTrue(player.data().pendingNotifications().isEmpty());
        assertNull(player.data().asMap());
    }

    @Test
    void linkedAccountUuidsFilterNonStringEntries() {
        Player player = Player.builder().build();
        player.data().asMap().put(PlayerDataKeys.LINKED_ACCOUNTS, Arrays.asList("alt-a", 7, null, "alt-b"));

        assertEquals(List.of("alt-a", "alt-b"), player.data().linkedAccountUuids());
    }

    @Test
    void pendingNotificationsFilterNonMapEntries() {
        Player player = Player.builder().build();
        List<Object> raw = new ArrayList<>();
        raw.add(Map.of("id", "n1"));
        raw.add("not-a-map");
        raw.add(Map.of("id", "n2"));
        player.data().asMap().put(PlayerDataKeys.PENDING_NOTIFICATIONS, raw);

        List<Map<String, Object>> notifications = player.data().pendingNotifications();
        assertEquals(2, notifications.size());
        assertEquals("n1", notifications.get(0).get("id"));
        assertEquals("n2", notifications.get(1).get("id"));
    }

    @Test
    void wrongTypedLegacyValuesAreTolerated() {
        Player player = Player.builder().build();
        player.data().asMap().put(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS, "lots");
        player.data().asMap().put(PlayerDataKeys.LAST_LOGIN, "yesterday");
        player.data().asMap().put(PlayerDataKeys.IS_ONLINE, "yes");

        assertNull(player.data().totalPlaytimeSeconds());
        assertNull(player.data().lastLogin());
        assertFalse(player.data().isOnline());
    }

    @Test
    void lastLoginReadsBackTypedDate() {
        Player player = Player.builder().build();
        Instant now = Instant.now();
        player.data().setLastLogin(now);

        assertEquals(now, player.data().lastLogin());
    }
}
