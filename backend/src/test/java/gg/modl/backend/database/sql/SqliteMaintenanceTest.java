package gg.modl.backend.database.sql;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMaintenanceTest {
    private static final int SMALL_MEBIBYTES = 16;
    private static final int MORE_THAN_ONE_NIGHT_MEBIBYTES = 420;

    @TempDir
    Path dataDirectory;

    @Test
    void freedPagesGoBackToTheDiskAndTheWriteAheadLogIsTruncated() throws SQLException, IOException {
        try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, dataDirectory)) {
            freePages(database, SMALL_MEBIBYTES);
            long before = number(database, "freelist_count");
            long pagesBefore = number(database, "page_count");

            maintenance(database).maintain();

            assertThat(before).isGreaterThan(SqliteMaintenance.PAGES_PER_UNIT);
            assertThat(number(database, "freelist_count")).isZero();
            assertThat(number(database, "page_count")).isLessThanOrEqualTo(pagesBefore - before);
            Path wal = dataDirectory.resolve("database").resolve(SqliteConnections.DATABASE_FILE_NAME + "-wal");
            assertThat(Files.exists(wal) ? Files.size(wal) : 0L).isZero();
        }
    }

    @Test
    void oneNightReleasesABoundedNumberOfPagesAndTheNextNightContinues() throws SQLException {
        try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, dataDirectory)) {
            freePages(database, MORE_THAN_ONE_NIGHT_MEBIBYTES);
            allocateStatisticsTables(database);
            long before = number(database, "freelist_count");
            long limit = (long) SqliteMaintenance.PAGES_PER_UNIT * SqliteMaintenance.MAX_UNITS_PER_NIGHT;
            assertThat(before).isGreaterThan(limit);

            maintenance(database).maintain();
            assertThat(number(database, "freelist_count")).isEqualTo(before - limit);

            maintenance(database).maintain();
            assertThat(number(database, "freelist_count")).isZero();
        }
    }

    private static SqliteMaintenance maintenance(SqlTestDatabase database) {
        return new SqliteMaintenance(database.bean(DatabaseConnections.class).sqlite(), MaintenanceTestAccess.of(database),
            database.dsl(), database.unitOfWork(), database.bean(WalCheckpoints.class),
            new ScheduledDatabaseJobs(database.bean(DatabaseWorkExecutor.class), () -> true));
    }

    private static void freePages(SqlTestDatabase database, int mebibytes) throws SQLException {
        ConnectionPools pools = database.bean(ConnectionPools.class);
        BulkPayload.fill(pools.writer(), mebibytes);
        try (Connection writer = pools.writer().getConnection(); Statement statement = writer.createStatement()) {
            statement.executeUpdate("DELETE FROM bulk_payload");
        }
    }

    private static void allocateStatisticsTables(SqlTestDatabase database) throws SQLException {
        try (Connection writer = database.bean(ConnectionPools.class).writer().getConnection();
             Statement statement = writer.createStatement()) {
            statement.executeUpdate("ANALYZE");
        }
    }

    private static long number(SqlTestDatabase database, String pragma) throws SQLException {
        try (Connection reader = database.bean(ConnectionPools.class).reader().getConnection()) {
            return SqlitePragmas.number(reader, pragma);
        }
    }
}
