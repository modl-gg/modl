package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.impl.menus.staff.OnlinePlayersMenu.OnlinePlayer;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OnlinePlayerPlaytimeTest {
    private static final long SECOND = 1000L;
    private static final long SNAPSHOT = 1_800_000_000_000L;

    @Test
    void totalPlaytimeIsEarlierPlaytimePlusTheLiveSession() {
        OnlinePlayer player = OnlinePlayer.fromSnapshot(UUID.randomUUID(), "Steve", SNAPSHOT - 4 * SECOND, 10 * SECOND, SNAPSHOT);

        assertEquals(4 * SECOND, player.sessionDurationAt(SNAPSHOT));
        assertEquals(10 * SECOND, player.totalPlaytimeAt(SNAPSHOT));
    }

    @Test
    void totalPlaytimeNeverFallsBelowTheSessionWhenTheSnapshotLagsTheJoin() {
        OnlinePlayer player = OnlinePlayer.fromSnapshot(UUID.randomUUID(), "Alex", SNAPSHOT - 12 * SECOND, 11 * SECOND, SNAPSHOT);

        assertEquals(player.sessionDurationAt(SNAPSHOT), player.totalPlaytimeAt(SNAPSHOT));
    }

    @Test
    void totalPlaytimeKeepsGrowingWithTheSessionAfterTheSnapshot() {
        OnlinePlayer player = OnlinePlayer.fromSnapshot(UUID.randomUUID(), "Notch", SNAPSHOT, 30 * SECOND, SNAPSHOT);
        long later = SNAPSHOT + 90 * SECOND;

        assertEquals(90 * SECOND, player.sessionDurationAt(later));
        assertEquals(120 * SECOND, player.totalPlaytimeAt(later));
    }
}
