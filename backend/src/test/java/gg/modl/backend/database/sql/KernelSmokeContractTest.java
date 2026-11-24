package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class KernelSmokeContractTest {
    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aWrittenRowIsReadBackAndASecondInsertIsAbsent(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            boolean first = database.unitOfWork().write(() -> database.insertIfAbsent().onPrimaryKey(SETTINGS).insert(general()));
            boolean second = database.unitOfWork().write(() -> database.insertIfAbsent().onPrimaryKey(SETTINGS).insert(general()));

            assertTrue(first);
            assertFalse(second);
            assertEquals("{}", database.dsl().fetchSingle(SETTINGS, SETTINGS.TYPE.eq("general")).getDataJson());
        }
    }

    private static SettingsRecord general() {
        return new SettingsRecord("general", "{}", 0L, SqlTestDatabase.START);
    }
}
