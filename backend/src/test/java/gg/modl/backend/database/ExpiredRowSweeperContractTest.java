package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.MINECRAFT_LINK_CODES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTIFICATIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.database.jooq.tables.records.MinecraftLinkCodesRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerNotificationsRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.io.TempDir;

class ExpiredRowSweeperContractTest {
    private static final int EXPIRED_PER_TABLE = 1_100;
    private static final int UNEXPIRED_PER_TABLE = 6;
    private static final List<String> PLAYERS = List.of("p1", "p2", "p3");
    private static final Instant NOW = KernelRows.NOW;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void expiredRowsAreDeletedInUnitsOfFiveHundredAndUnexpiredRowsStay(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            seed(database);
            BatchRecordingUnits units = new BatchRecordingUnits(database.unitOfWork());
            ExpiredRowSweeper sweeper = new ExpiredRowSweeper(List.of(
                new ExpiringRows(MINECRAFT_LINK_CODES, MINECRAFT_LINK_CODES.EXPIRES_AT),
                new ExpiringRows(PLAYER_NOTIFICATIONS, PLAYER_NOTIFICATIONS.EXPIRES_AT)), units, database.dsl(), database.clock());

            long deleted = sweeper.sweep();

            assertEquals(2L * EXPIRED_PER_TABLE, deleted);
            assertEquals(List.<Object>of(500, 500, 100, 500, 500, 100), units.results());
            assertUnexpiredRowsRemain(database);
        }
    }

    @SqlContractTest
    void theConfiguredSweeperDeletesEveryExpiredRowAcrossItsTables(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            seed(database);

            long deleted = database.bean(ExpiredRowSweeper.class).sweep();

            assertEquals(2L * EXPIRED_PER_TABLE, deleted);
            assertUnexpiredRowsRemain(database);
        }
    }

    private static void assertUnexpiredRowsRemain(SqlTestDatabase database) {
        assertEquals(UNEXPIRED_PER_TABLE, database.dsl().fetchCount(MINECRAFT_LINK_CODES));
        assertEquals(UNEXPIRED_PER_TABLE, database.dsl().fetchCount(PLAYER_NOTIFICATIONS));
        assertEquals(0, database.dsl().fetchCount(MINECRAFT_LINK_CODES, MINECRAFT_LINK_CODES.EXPIRES_AT.lt(NOW)));
        assertEquals(0, database.dsl().fetchCount(PLAYER_NOTIFICATIONS, PLAYER_NOTIFICATIONS.EXPIRES_AT.lt(NOW)));
    }

    private static void seed(SqlTestDatabase database) {
        PLAYERS.forEach(player -> KernelRows.insert(database, KernelRows.player(player)));
        List<MinecraftLinkCodesRecord> codes = new ArrayList<>();
        List<PlayerNotificationsRecord> notifications = new ArrayList<>();
        for (int row = 0; row < EXPIRED_PER_TABLE + UNEXPIRED_PER_TABLE; row++) {
            Instant expiresAt = expiry(row);
            codes.add(new MinecraftLinkCodesRecord("code-" + row, "hash-" + row, "user" + row + "@example.com", expiresAt, NOW));
            notifications.add(new PlayerNotificationsRecord(PLAYERS.get(row % PLAYERS.size()), "notification-" + row, "{}", NOW, expiresAt));
        }
        database.unitOfWork().write(() -> {
            database.dsl().batchInsert(codes).execute();
            database.dsl().batchInsert(notifications).execute();
        });
    }

    private static Instant expiry(int row) {
        if (row >= EXPIRED_PER_TABLE) {
            return NOW.plus(Duration.ofMinutes(row - EXPIRED_PER_TABLE));
        }
        return NOW.minus(Duration.ofSeconds(row + 1L));
    }

    private static final class BatchRecordingUnits implements UnitOfWork {
        private final UnitOfWork delegate;
        private final List<Object> results = new CopyOnWriteArrayList<>();

        private BatchRecordingUnits(UnitOfWork delegate) {
            this.delegate = delegate;
        }

        List<Object> results() {
            return List.copyOf(results);
        }

        @Override
        public <T> T write(Supplier<T> unit) {
            T result = delegate.write(unit);
            results.add(result);
            return result;
        }

        @Override
        public void write(Runnable unit) {
            delegate.write(unit);
        }

        @Override
        public <T> T read(Supplier<T> unit) {
            return delegate.read(unit);
        }

        @Override
        public <T> T report(Supplier<T> unit) {
            return delegate.report(unit);
        }

        @Override
        public void afterCommit(Runnable effect) {
            delegate.afterCommit(effect);
        }
    }
}
