package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.database.jooq.tables.records.AuthCodesRecord;
import gg.modl.backend.database.jooq.tables.records.ChatLogsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerUsernamesRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jooq.Condition;
import org.jooq.Field;
import org.junit.jupiter.api.io.TempDir;

class CollationContractTest {
    private static final String PLAYER = "p1";
    private static final List<String> USERNAMES = List.of(
        "a/b", "a/c", "a0", "a.b", "ab", "abc", "steve", "stevie", "uber", "über", "üz", "ý",
        "𝔘ber", "𝔘x", "𝔙a", "50%off", "50xoff", "5_0", "5x0", "a!b", "axb");
    private static final List<Instant> SENT = List.of(
        Instant.parse("2026-01-01T00:00:00.000Z"),
        Instant.parse("2026-01-01T23:59:59.999Z"),
        Instant.parse("2026-01-02T00:00:00.000Z"),
        Instant.parse("2026-01-02T00:00:00.001Z"),
        Instant.parse("2026-01-04T12:30:00.000Z"),
        Instant.parse("1970-01-01T00:00:00.000Z"));

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aPrefixEndingInASlashStopsBeforeTheNextCharacter(DialectTarget target) {
        try (SqlTestDatabase database = withUsernames(target)) {
            assertEquals(Set.of("a/b", "a/c"), usernames(database, PrefixRange.of(PLAYER_USERNAMES.USERNAME_LOWER, "a/")));
            assertEquals(Set.of("a/b", "a/c", "a0", "a.b", "ab", "abc", "a!b", "axb"),
                usernames(database, PrefixRange.of(PLAYER_USERNAMES.USERNAME_LOWER, "a")));
        }
    }

    @SqlContractTest
    void anUpperCasePrefixDoesNotMatchLowerCaseRows(DialectTarget target) {
        try (SqlTestDatabase database = withUsernames(target)) {
            assertEquals(Set.of(), usernames(database, PrefixRange.of(PLAYER_USERNAMES.USERNAME_LOWER, "STE")));
            assertEquals(Set.of("steve", "stevie"), usernames(database, PrefixRange.of(PLAYER_USERNAMES.USERNAME_LOWER, "ste")));
        }
    }

    @SqlContractTest
    void nonAsciiPrefixesMatchByCodePoint(DialectTarget target) {
        try (SqlTestDatabase database = withUsernames(target)) {
            assertEquals(Set.of("über", "üz"), usernames(database, PrefixRange.of(PLAYER_USERNAMES.USERNAME_LOWER, "ü")));
            assertEquals(Set.of("𝔘ber", "𝔘x"),
                usernames(database, PrefixRange.of(PLAYER_USERNAMES.USERNAME_LOWER, "𝔘")));
        }
    }

    @SqlContractTest
    void anEmptyPrefixMatchesEveryRow(DialectTarget target) {
        try (SqlTestDatabase database = withUsernames(target)) {
            assertEquals(Set.copyOf(USERNAMES), usernames(database, PrefixRange.of(PLAYER_USERNAMES.USERNAME_LOWER, "")));
        }
    }

    @SqlContractTest
    void containsTreatsLikeWildcardsAndTheEscapeCharacterLiterally(DialectTarget target) {
        try (SqlTestDatabase database = withUsernames(target)) {
            assertEquals(Set.of("50%off"), usernames(database, PLAYER_USERNAMES.USERNAME_LOWER.contains("%")));
            assertEquals(Set.of("5_0"), usernames(database, PLAYER_USERNAMES.USERNAME_LOWER.contains("_")));
            assertEquals(Set.of("a!b"), usernames(database, PLAYER_USERNAMES.USERNAME_LOWER.contains("!")));
            assertEquals(Set.of("50%off"), usernames(database, PLAYER_USERNAMES.USERNAME_LOWER.contains("0%o")));
        }
    }

    @SqlContractTest
    void dayBucketsStartAtUtcMidnight(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insertAll(database, IntStream.range(0, SENT.size())
                .mapToObj(row -> new ChatLogsRecord((long) row + 1, PLAYER, "Steve", "line", "lobby", SENT.get(row)))
                .toList());
            Field<Long> dayStart = TimeBuckets.dayStart(CHAT_LOGS.SENT_AT);

            Map<Long, Long> bucketById = database.dsl().select(CHAT_LOGS.ID, dayStart).from(CHAT_LOGS)
                .fetchMap(CHAT_LOGS.ID, dayStart);

            Map<Long, Long> expected = IntStream.range(0, SENT.size()).boxed()
                .collect(Collectors.toMap(row -> (long) row + 1, row -> utcMidnightMillis(SENT.get(row))));
            assertEquals(expected, bucketById);
        }
    }

    @SqlContractTest
    void hashesThatDifferOnlyInCaseDoNotMatchByEquality(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Instant expires = KernelRows.NOW.plus(Duration.ofHours(1));
            KernelRows.insert(database,
                new AuthCodesRecord("lower@example.com", "a1b2c3d4", 0, KernelRows.NOW, expires, 0, null, expires),
                new AuthCodesRecord("upper@example.com", "A1B2C3D4", 0, KernelRows.NOW, expires, 0, null, expires));

            assertEquals(List.of("lower@example.com"), emailsWithHash(database, "a1b2c3d4"));
            assertEquals(List.of("upper@example.com"), emailsWithHash(database, "A1B2C3D4"));
            assertEquals(List.of(), emailsWithHash(database, "A1b2C3d4"));
        }
    }

    private SqlTestDatabase withUsernames(DialectTarget target) {
        SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory);
        KernelRows.insert(database, KernelRows.player(PLAYER));
        KernelRows.insertAll(database, USERNAMES.stream()
            .map(name -> new PlayerUsernamesRecord(PLAYER, name, name, KernelRows.NOW, KernelRows.NOW))
            .toList());
        return database;
    }

    private static Set<String> usernames(SqlTestDatabase database, Condition condition) {
        return Set.copyOf(database.dsl().select(PLAYER_USERNAMES.USERNAME_LOWER).from(PLAYER_USERNAMES).where(condition)
            .fetch(PLAYER_USERNAMES.USERNAME_LOWER));
    }

    private static List<String> emailsWithHash(SqlTestDatabase database, String hash) {
        return database.dsl().select(AUTH_CODES.EMAIL).from(AUTH_CODES).where(AUTH_CODES.CODE_HASH.eq(hash)).fetch(AUTH_CODES.EMAIL);
    }

    private static long utcMidnightMillis(Instant instant) {
        long millis = instant.toEpochMilli();
        return millis - Math.floorMod(millis, TimeBuckets.DAY_MILLIS);
    }
}
