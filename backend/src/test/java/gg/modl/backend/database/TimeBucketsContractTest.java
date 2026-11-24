package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class TimeBucketsContractTest {
    private static final List<Instant> MOMENTS = List.of(
        Instant.parse("1969-12-30T22:15:30.001Z"),
        Instant.parse("1969-12-31T00:00:00Z"),
        Instant.EPOCH,
        Instant.parse("2026-11-22T13:59:59.999Z"));

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void dayAndHourBucketsFloorBeforeAndAfterTheEpoch(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            for (Instant moment : MOMENTS) {
                database.unitOfWork().write(() -> {
                    database.dsl().deleteFrom(SETTINGS).execute();
                    database.dsl().insertInto(SETTINGS)
                        .set(SETTINGS.TYPE, "general")
                        .set(SETTINGS.DATA_JSON, "{}")
                        .set(SETTINGS.VERSION, 1L)
                        .set(SETTINGS.UPDATED_AT, moment)
                        .execute();
                });

                assertEquals(moment.truncatedTo(ChronoUnit.DAYS).toEpochMilli(),
                    database.dsl().select(TimeBuckets.dayStart(SETTINGS.UPDATED_AT)).from(SETTINGS).fetchSingle().value1());
                assertEquals(moment.truncatedTo(ChronoUnit.HOURS).toEpochMilli(),
                    database.dsl().select(TimeBuckets.hourStart(SETTINGS.UPDATED_AT)).from(SETTINGS).fetchSingle().value1());
            }
        }
    }
}
