package gg.modl.backend.hostedimport.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourcePlayerIndexTest {
    private static final int PLAYERS = 200_000;

    @Test
    void everyObjectIdResolvesToItsOwnPlayerAfterTheTableGrows() {
        SourcePlayerIndex index = new SourcePlayerIndex();
        for (int i = 0; i < PLAYERS; i++) {
            index.putIfAbsent(objectId(i), uuid(i));
        }

        assertThat(index.size()).isEqualTo(PLAYERS);
        for (int i = 0; i < PLAYERS; i += 997) {
            assertThat(index.uuidOf(objectId(i))).isEqualTo(uuid(i).toString());
        }
        assertThat(index.uuidOf(objectId(PLAYERS + 1))).isNull();
    }

    @Test
    void theFirstPlayerOfASourceIdWins() {
        SourcePlayerIndex index = new SourcePlayerIndex();
        index.putIfAbsent(objectId(1), uuid(1));
        index.putIfAbsent(objectId(1), uuid(2));
        index.putIfAbsent("p1", uuid(3));
        index.putIfAbsent("p1", uuid(4));

        assertThat(index.uuidOf(objectId(1))).isEqualTo(uuid(1).toString());
        assertThat(index.uuidOf("p1")).isEqualTo(uuid(3).toString());
        assertThat(index.size()).isEqualTo(2);
    }

    @Test
    void anUpperCaseIdIsNotTakenForTheLowerCaseObjectId() {
        SourcePlayerIndex index = new SourcePlayerIndex();
        index.putIfAbsent(objectId(7), uuid(7));

        assertThat(index.uuidOf(objectId(7).toUpperCase(Locale.ROOT))).isNull();
        assertThat(index.uuidOf(null)).isNull();
    }

    @Test
    void aUuidSetReportsEachUuidOnceAcrossGrowth() {
        UuidSet seen = new UuidSet();
        for (int i = 0; i < PLAYERS; i++) {
            assertThat(seen.add(uuid(i))).isTrue();
        }

        assertThat(seen.add(uuid(42))).isFalse();
        assertThat(seen.add(new UUID(0, 0))).isTrue();
        assertThat(seen.add(new UUID(0, 0))).isFalse();
    }

    private static String objectId(int i) {
        return String.format(Locale.ROOT, "665f1c2e%016x", (long) i * 2_654_435_761L);
    }

    private static UUID uuid(int i) {
        return new UUID(0x0b7e8a3c6c554b3aL ^ i, 0x9d8a000000000000L | i);
    }
}
