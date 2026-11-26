package gg.modl.backend.database.backup;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.jooq.tables.records.AuditLogsRecord;
import gg.modl.backend.database.sql.MigrationSteps;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.io.TempDir;

class OlderArchiveRestoreContractTest {
    private static final String BEFORE_ROLLBACK_COLUMN_DROP = "10";
    private static final String DROPPED_COLUMN = "can_rollback";
    private static final long LOG_ID = 7_000_001L;
    private static final Instant CREATED = SqlTestDatabase.START;

    @TempDir
    Path directory;

    @SqlContractTest
    void aLogicalArchiveOfVersionTenRestoresItsAuditLogWithoutTheDroppedColumn(DialectTarget target) throws Exception {
        assertRestoresWithoutTheDroppedColumn(target, target, ArchiveKind.LOGICAL);
    }

    @SqlContractTest
    void aBuiltInSnapshotOfVersionTenRestoresItsAuditLogWithoutTheDroppedColumn(DialectTarget target) throws Exception {
        assertRestoresWithoutTheDroppedColumn(TestDialects.SQLITE, target, ArchiveKind.SNAPSHOT);
    }

    private void assertRestoresWithoutTheDroppedColumn(DialectTarget source, DialectTarget target, ArchiveKind kind) throws Exception {
        BackupScenario older = BackupScenario.fresh(source, directory.resolve("older"));
        MigrationSteps steps = MigrationSteps.on(source, older.database(), older.dataDirectory());
        assertThat(steps.migrateTo(BEFORE_ROLLBACK_COLUMN_DROP)).isEqualTo(BEFORE_ROLLBACK_COLUMN_DROP);
        insertVersionTenAuditLog(steps);
        Path archive = directory.resolve("version-ten.zip");
        older.write(kind, archive);
        assertThat(TestArchives.manifest(archive)).contains("\"schemaVersion\":\"" + BEFORE_ROLLBACK_COLUMN_DROP + "\"");

        BackupScenario current = BackupScenario.fresh(target, directory.resolve("current"));
        assertThat(current.contents()).isNotEmpty();
        current.restore(archive);

        try (SqlTestDatabase restored = current.start()) {
            AuditLogsRecord log = restored.dsl().selectFrom(AUDIT_LOGS).fetchSingle();
            assertThat(log.getId()).isEqualTo(LOG_ID);
            assertThat(log.getLevel()).isEqualTo("moderation");
            assertThat(log.getSource()).isEqualTo("Alice");
            assertThat(log.getDescription()).isEqualTo("Rolled back a ban");
            assertThat(log.getMetadataJson()).isEqualTo("{\"punishmentId\":\"P1\"}");
            assertThat(log.getCreatedAt()).isEqualTo(CREATED);
            assertThat(restored.dsl().connectionResult(OlderArchiveRestoreContractTest::auditLogColumns))
                .isNotEmpty().doesNotContain(DROPPED_COLUMN);
        }
    }

    private static List<String> auditLogColumns(Connection connection) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet found = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), "%", "%")) {
            while (found.next()) {
                if (AUDIT_LOGS.getName().equalsIgnoreCase(found.getString("TABLE_NAME"))) {
                    columns.add(found.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    private static void insertVersionTenAuditLog(MigrationSteps steps) throws SQLException {
        try (Connection connection = steps.connections().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO modl_audit_logs (id, level, source, description, metadata_json, " + DROPPED_COLUMN
                + ", created_at) VALUES (" + LOG_ID + ", 'moderation', 'Alice', 'Rolled back a ban', '{\"punishmentId\":\"P1\"}', "
                + "FALSE, " + CREATED.toEpochMilli() + ")");
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
    }
}
