package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class PrefixRangeTest {
    private static final String MAX_CODE_POINT = new String(Character.toChars(Character.MAX_CODE_POINT));

    @Test
    void theUpperBoundIncrementsTheLastCodePoint() {
        assertEquals(Optional.of("abd"), PrefixRange.upperBound("abc"));
    }

    @Test
    void theUpperBoundSkipsTheSurrogateGap() {
        assertEquals(Optional.of("a"), PrefixRange.upperBound("a퟿"));
    }

    @Test
    void aSupplementaryLastCodePointIncrementsAsOneCodePoint() {
        String grinning = new String(Character.toChars(0x1F600));
        String next = new String(Character.toChars(0x1F601));

        assertEquals(Optional.of("a" + next), PrefixRange.upperBound("a" + grinning));
    }

    @Test
    void theMaximumCodePointCarriesIntoThePreviousCodePoint() {
        assertEquals(Optional.of("b"), PrefixRange.upperBound("a" + MAX_CODE_POINT));
    }

    @Test
    void aPrefixOfOnlyMaximumCodePointsHasNoUpperBound() {
        assertEquals(Optional.empty(), PrefixRange.upperBound(MAX_CODE_POINT.repeat(2)));
    }

    @Test
    void aPrefixMatchesTheHalfOpenRangeUpToItsUpperBound() {
        assertEquals(PLAYERS.USERNAME_LOWER.ge("ab").and(PLAYERS.USERNAME_LOWER.lt("ac")),
            PrefixRange.of(PLAYERS.USERNAME_LOWER, "ab"));
    }

    @Test
    void aPrefixWithoutUpperBoundHasOnlyTheLowerBound() {
        assertEquals(PLAYERS.USERNAME_LOWER.ge(MAX_CODE_POINT), PrefixRange.of(PLAYERS.USERNAME_LOWER, MAX_CODE_POINT));
    }

    @Test
    void anEmptyPrefixMatchesEverything() {
        assertEquals(DSL.trueCondition(), PrefixRange.of(PLAYERS.USERNAME_LOWER, ""));
    }
}
