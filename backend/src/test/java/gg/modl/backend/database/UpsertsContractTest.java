package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.MINECRAFT_LINK_CODES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.modl.backend.database.jooq.tables.records.MinecraftLinkCodesRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerIpsRecord;
import gg.modl.backend.database.jooq.tables.records.StaffRolesRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jooq.Field;
import org.junit.jupiter.api.io.TempDir;

class UpsertsContractTest {
    private static final String PLAYER = "p1";
    private static final String IP = "203.0.113.7";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anUpsertIsRefusedOnATableWithAnotherUniqueIndex(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            StaffRolesRecord role = new StaffRolesRecord("r1", "Admin", "admin", null, false, 1, KernelRows.NOW, KernelRows.NOW);
            Map<Field<?>, Object> rename = Map.of(STAFF_ROLES.NAME, "Owner");

            assertThrows(IllegalArgumentException.class, () -> database.unitOfWork().write(() ->
                database.upserts().onPrimaryKey(STAFF_ROLES, role, rename)));
        }
    }

    @SqlContractTest
    void anUpsertIsRefusedOnATableWithTwoOtherUniqueIndexes(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftLinkCodesRecord code = new MinecraftLinkCodesRecord("c1", "hash", "a@example.com", KernelRows.NOW, KernelRows.NOW);
            Map<Field<?>, Object> rehash = Map.of(MINECRAFT_LINK_CODES.CODE_HASH, "other");

            assertThrows(IllegalArgumentException.class, () -> database.unitOfWork().write(() ->
                database.upserts().onPrimaryKey(MINECRAFT_LINK_CODES, code, rehash)));
        }
    }

    @SqlContractTest
    void anUpsertWithoutAssignmentsIsRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            assertThrows(IllegalArgumentException.class, () -> database.unitOfWork().write(() ->
                database.upserts().onPrimaryKey(SETTINGS, KernelRows.settings("general"), Map.of())));
            assertEquals(0, database.dsl().fetchCount(SETTINGS));
        }
    }

    @SqlContractTest
    void aFirstLoginUpsertInsertsTheIpWithACountOfOne(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.player(PLAYER));

            recordLogin(database, KernelRows.NOW);

            PlayerIpsRecord stored = storedIp(database);
            assertEquals(KernelRows.NOW, stored.getFirstSeenAt());
            assertEquals(KernelRows.NOW, stored.getLastSeenAt());
            assertEquals(1L, stored.getLoginCount());
        }
    }

    @SqlContractTest
    void aRepeatedLoginUpsertIncrementsTheCountAndKeepsTheFirstSighting(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.player(PLAYER));
            Instant later = KernelRows.NOW.plus(Duration.ofHours(5));
            Instant latest = later.plus(Duration.ofMinutes(3));

            recordLogin(database, KernelRows.NOW);
            recordLogin(database, later);
            recordLogin(database, latest);

            PlayerIpsRecord stored = storedIp(database);
            assertEquals(KernelRows.NOW, stored.getFirstSeenAt());
            assertEquals(latest, stored.getLastSeenAt());
            assertEquals(3L, stored.getLoginCount());
            assertEquals(1, database.dsl().fetchCount(PLAYER_IPS));
        }
    }

    private static void recordLogin(SqlTestDatabase database, Instant at) {
        PlayerIpsRecord row = new PlayerIpsRecord(PLAYER, IP, null, null, null, false, false, at, at, 1L, null);
        Map<Field<?>, Object> seenAgain = Map.of(
            PLAYER_IPS.LAST_SEEN_AT, at,
            PLAYER_IPS.LOGIN_COUNT, PLAYER_IPS.LOGIN_COUNT.plus(1L));
        database.unitOfWork().write(() -> database.upserts().onPrimaryKey(PLAYER_IPS, row, seenAgain));
    }

    private static PlayerIpsRecord storedIp(SqlTestDatabase database) {
        return database.dsl().fetchSingle(PLAYER_IPS, PLAYER_IPS.PLAYER_ID.eq(PLAYER).and(PLAYER_IPS.IP.eq(IP)));
    }
}
