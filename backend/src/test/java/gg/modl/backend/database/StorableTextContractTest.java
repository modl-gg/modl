package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;

class StorableTextContractTest {
    private static final String WITH_NUL = "before\u0000after";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aWrittenNulIsRefusedOnEveryDialectAndStoresNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            assertThrows(UnstorableTextException.class, () -> database.unitOfWork().write(() -> {
                database.dsl().insertInto(SETTINGS)
                    .set(SETTINGS.TYPE, "general")
                    .set(SETTINGS.DATA_JSON, WITH_NUL)
                    .set(SETTINGS.VERSION, 1L)
                    .set(SETTINGS.UPDATED_AT, SqlTestDatabase.START)
                    .execute();
            }));

            assertEquals(0, database.dsl().fetchCount(SETTINGS));
        }
    }

    @SqlContractTest
    void aBatchWithANulIsRefusedOnEveryDialect(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            var row = database.dsl().newRecord(SETTINGS);
            row.setType("general");
            row.setDataJson(WITH_NUL);
            row.setVersion(1L);
            row.setUpdatedAt(SqlTestDatabase.START);

            assertThrows(UnstorableTextException.class, () -> database.unitOfWork().write(() -> {
                database.dsl().batchInsert(row).execute();
            }));
        }
    }

    @SqlContractTest
    void aBoundNulInAQueryIsRefusedInsteadOfFailingInTheDriver(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            assertThrows(UnstorableTextException.class,
                () -> database.dsl().fetchCount(SETTINGS, SETTINGS.DATA_JSON.containsIgnoreCase(WITH_NUL)));
        }
    }

    @SqlContractTest
    void aLookupWithANulMatchesNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            assertFalse(database.dsl().fetchExists(AUTH_CODES, ColumnLookups.matches(AUTH_CODES.EMAIL, WITH_NUL)));
        }
    }

    @SqlContractTest
    void aJsonColumnEscapesTheNul(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JsonColumns json = database.jsonColumns();
            database.unitOfWork().write(() -> {
                database.dsl().insertInto(SETTINGS)
                    .set(SETTINGS.TYPE, "general")
                    .set(SETTINGS.DATA_JSON, json.write(Map.of("text", WITH_NUL)))
                    .set(SETTINGS.VERSION, 1L)
                    .set(SETTINGS.UPDATED_AT, SqlTestDatabase.START)
                    .execute();
            });

            assertEquals(WITH_NUL, json.readMap(database.dsl().fetchSingle(SETTINGS).getDataJson()).get("text"));
        }
    }
}
