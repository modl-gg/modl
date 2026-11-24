package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class FreeTextContractTest {
    private static final String STORED = "Привет École 100% snake_case!";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aTermTypedExactlyAsStoredIsFoundOnEveryDialect(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            database.unitOfWork().write(() -> {
                database.dsl().insertInto(SETTINGS)
                    .set(SETTINGS.TYPE, "general")
                    .set(SETTINGS.DATA_JSON, STORED)
                    .set(SETTINGS.VERSION, 1L)
                    .set(SETTINGS.UPDATED_AT, SqlTestDatabase.START)
                    .execute();
            });

            for (String term : List.of("Привет", "École", "ÉCOLE", "100%", "SNAKE_CASE!")) {
                assertEquals(1, database.dsl().fetchCount(SETTINGS, FreeText.contains(SETTINGS.DATA_JSON, term)), term);
            }
            for (String absent : List.of("100%%", "snakeXcase")) {
                assertEquals(0, database.dsl().fetchCount(SETTINGS, FreeText.contains(SETTINGS.DATA_JSON, absent)), absent);
            }
        }
    }
}
