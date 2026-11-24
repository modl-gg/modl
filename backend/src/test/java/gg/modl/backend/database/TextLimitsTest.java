package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextLimitsTest {
    private static final String SUPPLEMENTARY = "😀";

    @Test
    void theLimitIsTheGeneratedColumnLength() {
        assertEquals(100, TextLimits.limit(PLAYERS.USERNAME));
        assertEquals(36, TextLimits.limit(PLAYERS.MINECRAFT_UUID));
    }

    @Test
    void theLengthIsCountedInCodePoints() {
        String value = SUPPLEMENTARY.repeat(100);

        assertEquals(value, TextLimits.require(PLAYERS.USERNAME, value));
    }

    @Test
    void aValueOverTheLimitIsRejected() {
        ValueTooLongException failure = assertThrows(ValueTooLongException.class,
            () -> TextLimits.require(PLAYERS.USERNAME, SUPPLEMENTARY.repeat(101)));

        assertTrue(failure.getMessage().contains("100"), failure.getMessage());
        assertFalse(failure.getMessage().contains(PLAYERS.USERNAME.getName()), failure.getMessage());
    }

    @Test
    void trailingSpacesAreStrippedForAnIndexedColumn() {
        assertTrue(TextLimits.isIndexed(PLAYERS.MINECRAFT_UUID));
        assertEquals(" abc", TextLimits.require(PLAYERS.MINECRAFT_UUID, " abc   "));
    }

    @Test
    void aPrimaryKeyColumnCountsAsIndexed() {
        assertTrue(TextLimits.isIndexed(PLAYER_USERNAMES.USERNAME_LOWER));
        assertEquals("steve", TextLimits.require(PLAYER_USERNAMES.USERNAME_LOWER, "steve "));
    }

    @Test
    void trailingSpacesAreKeptForAnUnindexedColumn() {
        assertFalse(TextLimits.isIndexed(PLAYERS.USERNAME));
        assertEquals("abc  ", TextLimits.require(PLAYERS.USERNAME, "abc  "));
    }

    @Test
    void onlySpacesAreStripped() {
        assertEquals("abc\t", TextLimits.require(PLAYERS.MINECRAFT_UUID, "abc\t "));
    }

    @Test
    void strippedSpacesDoNotCountTowardTheLimit() {
        String value = "a".repeat(36);

        assertEquals(value, TextLimits.require(PLAYERS.MINECRAFT_UUID, value + "    "));
    }

    @Test
    void keptSpacesCountTowardTheLimit() {
        assertThrows(ValueTooLongException.class, () -> TextLimits.require(PLAYERS.USERNAME, "a".repeat(100) + " "));
    }

    @Test
    void aTextColumnHasNoLimit() {
        String value = "r".repeat(100_000);

        assertEquals(value, TextLimits.require(PUNISHMENTS.REASON, value));
    }

    @Test
    void nullPassesThrough() {
        assertNull(TextLimits.requireIfPresent(PLAYERS.USERNAME, null));
    }
}
