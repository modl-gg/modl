package gg.modl.minecraft.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentShorteningTest {
    private static final long NOW = 1_780_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    @Test
    void permanentToFiniteShortensAndFiniteToPermanentLengthens() {
        assertTrue(started(null, NOW - HOUR).endsEarlierWith(365 * DAY, NOW));
        assertFalse(started(DAY, NOW - HOUR).endsEarlierWith(null, NOW));
        assertFalse(started(DAY, NOW - HOUR).endsEarlierWith(-1L, NOW));
        assertFalse(started(null, NOW - HOUR).endsEarlierWith(null, NOW));
    }

    @Test
    void aStartedPunishmentIsComparedByItsEndAndTheSameEndIsNoChange() {
        Punishment punishment = started(DAY, NOW - 6 * HOUR);

        assertTrue(punishment.endsEarlierWith(17 * HOUR, NOW));
        assertFalse(punishment.endsEarlierWith(18 * HOUR, NOW));
        assertFalse(punishment.endsEarlierWith(2 * DAY, NOW));
    }

    @Test
    void anEarlierDurationChangeSetsTheCurrentEnd() {
        Punishment punishment = started(30 * DAY, NOW - 10 * DAY, new Modification(
                Modification.Type.MANUAL_DURATION_CHANGE, "Mod", Instant.ofEpochMilli(NOW - 12 * HOUR), DAY));

        assertTrue(punishment.endsEarlierWith(6 * HOUR, NOW));
        assertFalse(punishment.endsEarlierWith(12 * HOUR, NOW));
    }

    @Test
    void queuedAndNotStartedPunishmentsAreComparedByTheirLength() {
        Map<String, Object> queued = new HashMap<>();
        queued.put("duration", 7 * DAY);
        queued.put("status", "Unstarted");
        Punishment waiting = Punishment.builder().id("P").typeOrdinal(2).dataMap(queued).build();
        Punishment notStarted = Punishment.builder().id("P").typeOrdinal(2)
                .dataMap(Collections.singletonMap("duration", (Object) (7 * DAY))).build();

        assertTrue(waiting.endsEarlierWith(6 * DAY, NOW));
        assertFalse(waiting.endsEarlierWith(7 * DAY, NOW));
        assertTrue(notStarted.endsEarlierWith(6 * DAY, NOW));
        assertFalse(notStarted.endsEarlierWith(8 * DAY, NOW));
    }

    @Test
    void aPunishmentThatHasNotStartedKeepsItsClockStoppedAfterADurationChange() {
        List<Modification> changes = new ArrayList<>();
        changes.add(new Modification(Modification.Type.MANUAL_DURATION_CHANGE, "Mod", Instant.ofEpochMilli(NOW - 2 * DAY), 3 * DAY));
        Punishment notStarted = Punishment.builder().id("P").typeOrdinal(2).modifications(changes)
                .dataMap(Collections.singletonMap("duration", (Object) (7 * DAY))).build();

        assertTrue(notStarted.endsEarlierWith(2 * DAY, NOW));
        assertFalse(notStarted.endsEarlierWith(3 * DAY, NOW));
        assertNull(notStarted.getDurationStart());
    }

    @Test
    void aDurationChangeBeforeTheStartRunsFromTheStart() {
        Punishment punishment = started(30 * DAY, NOW - HOUR, new Modification(
                Modification.Type.MANUAL_DURATION_CHANGE, "Mod", Instant.ofEpochMilli(NOW - DAY), DAY));

        assertEquals(Instant.ofEpochMilli(NOW - HOUR), punishment.getDurationStart());
        assertTrue(punishment.endsEarlierWith(22 * HOUR, NOW));
        assertFalse(punishment.endsEarlierWith(23 * HOUR, NOW));
    }

    @Test
    void endedPunishmentsAndKicksAreNeverShortened() {
        Punishment pardoned = started(DAY, NOW - HOUR, new Modification(Modification.Type.MANUAL_PARDON, "Mod", Instant.ofEpochMilli(NOW), null));
        Punishment kick = Punishment.builder().id("K").type(Punishment.Type.KICK).started(Instant.ofEpochMilli(NOW - HOUR)).build();

        assertFalse(pardoned.endsEarlierWith(HOUR, NOW));
        assertFalse(kick.endsEarlierWith(HOUR, NOW));
    }

    private static Punishment started(Long duration, long startedAt, Modification... modifications) {
        Map<String, Object> data = new HashMap<>();
        if (duration != null) data.put("duration", duration);
        List<Modification> changes = new ArrayList<>();
        Collections.addAll(changes, modifications);
        return Punishment.builder().id("P").typeOrdinal(2).started(Instant.ofEpochMilli(startedAt)).modifications(changes).dataMap(data).build();
    }
}
