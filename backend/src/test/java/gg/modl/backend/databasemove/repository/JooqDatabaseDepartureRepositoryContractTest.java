package gg.modl.backend.databasemove.repository;

import static gg.modl.backend.database.jooq.Tables.DATABASE_DEPARTURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.database.sql.DatabaseDeparture;
import gg.modl.backend.database.sql.DepartureMarks;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.io.TempDir;

class JooqDatabaseDepartureRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aNewMarkReplacesTheOldOneAndClearRemovesIt(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqDatabaseDepartureRepository repository = new JooqDatabaseDepartureRepository(database.dsl());
            database.unitOfWork().write(() -> repository.mark(new DatabaseDeparture("first", "MySQL at db:3306/modl", NOW)));
            database.unitOfWork().write(() -> repository.mark(new DatabaseDeparture("second", "PostgreSQL at pg:5432/modl", NOW)));

            assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isEqualTo(1);
            assertThat(DepartureMarks.read(database.bean(Flyway.class).getConfiguration().getDataSource(), database.dialect()))
                .contains(new DatabaseDeparture("second", "PostgreSQL at pg:5432/modl", NOW));

            database.unitOfWork().write(repository::clear);
            assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isZero();
            database.unitOfWork().write(repository::clear);
            assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isZero();
        }
    }

    @SqlContractTest
    void overLongValuesAreRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqDatabaseDepartureRepository repository = new JooqDatabaseDepartureRepository(database.dsl());

            assertThatThrownBy(() -> database.unitOfWork().write(() -> repository.mark(new DatabaseDeparture("t", "x".repeat(513), NOW))))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> database.unitOfWork().write(() -> repository.mark(new DatabaseDeparture("t".repeat(65), "copy", NOW))))
                .isInstanceOf(ValueTooLongException.class);
            assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isZero();
        }
    }
}
