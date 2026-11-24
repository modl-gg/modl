package gg.modl.backend.audit.repository;

import static gg.modl.backend.analytics.repository.ReadModelRows.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.analytics.repository.ReadModelRows;
import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class AdminTableBrowserRepositoryContractTest {
    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void pagesAreNewestFirstWithTheKeyAsTieBreak(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                ReadModelRows.ticket("T-a", "support", "open", NOW, NOW),
                ReadModelRows.ticket("T-c", "support", "open", NOW, NOW),
                ReadModelRows.ticket("T-b", "support", "open", NOW, NOW),
                ReadModelRows.ticket("T-old", "support", "open", NOW.minusSeconds(1), NOW));
            JooqAdminTableBrowserRepository repository = repository(database);

            assertThat(repository.readPage(BrowsableTable.TICKETS, 2, 0)).extracting(row -> row.get("id"))
                .containsExactly("T-c", "T-b");
            assertThat(repository.readPage(BrowsableTable.TICKETS, 2, 2)).extracting(row -> row.get("id"))
                .containsExactly("T-a", "T-old");
            assertThat(repository.readPage(BrowsableTable.TICKETS, 2, 4)).isEmpty();
            assertThat(repository.count(BrowsableTable.TICKETS)).isEqualTo(4);
        }
    }

    @SqlContractTest
    void settingsAndLogsUseTheirOwnKeys(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReadModelRows.insert(database,
                new SettingsRecord("general", "{}", 0L, NOW),
                new SettingsRecord("mail", "{}", 0L, NOW),
                ReadModelRows.log(1, "info", "alice", "first", NOW),
                ReadModelRows.log(2, "info", "alice", "second", NOW));
            JooqAdminTableBrowserRepository repository = repository(database);

            assertThat(repository.readPage(BrowsableTable.SETTINGS, 10, 0)).extracting(row -> row.get("type"))
                .containsExactly("mail", "general");
            assertThat(repository.readPage(BrowsableTable.AUDIT_LOGS, 10, 0)).extracting(row -> row.get("description"))
                .containsExactly("second", "first");
        }
    }

    @SqlContractTest
    void everyTableCanBeReadWhenEmpty(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqAdminTableBrowserRepository repository = repository(database);

            for (BrowsableTable table : BrowsableTable.values()) {
                assertThat(repository.readPage(table, 10, 0)).as(table.name()).isEmpty();
                assertThat(repository.count(table)).as(table.name()).isZero();
            }
        }
    }

    private static JooqAdminTableBrowserRepository repository(SqlTestDatabase database) {
        return new JooqAdminTableBrowserRepository(database.dsl(), database.unitOfWork());
    }
}
