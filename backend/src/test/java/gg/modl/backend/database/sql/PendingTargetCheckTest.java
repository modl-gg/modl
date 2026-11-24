package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.DATABASE_DEPARTURE;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.databasemove.repository.JooqDatabaseDepartureRepository;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PendingTargetCheckTest {
    private static final String TOKEN = "move-token";

    @TempDir
    Path directory;

    @Test
    void aMissingOrUnreadableBuiltInDatabaseIsNotTheCopy() throws IOException {
        DataDirectoryProperties dataDirectory = new DataDirectoryProperties(directory.toString());

        assertThat(PendingTargetCheck.check(SqlDatabaseProperties.builtIn(), dataDirectory, TOKEN))
            .isEqualTo(PendingTargetCheck.Result.refused(PendingTargetCheck.NO_FILE));

        Files.createDirectories(dataDirectory.database());
        Files.writeString(dataDirectory.database().resolve(SqliteConnections.DATABASE_FILE_NAME), "content");
        assertThat(PendingTargetCheck.check(SqlDatabaseProperties.builtIn(), dataDirectory, TOKEN).outcome())
            .isEqualTo(PendingTargetCheck.Outcome.REFUSED);
    }

    @Test
    void aBuiltInDatabaseIsTheCopyOnlyWhenItCarriesTheMoveMark() {
        DataDirectoryProperties dataDirectory = new DataDirectoryProperties(directory.toString());
        try (SqlTestDatabase built = SqlTestDatabase.start(TestDialects.SQLITE, directory)) {
            assertThat(PendingTargetCheck.check(SqlDatabaseProperties.builtIn(), dataDirectory, TOKEN))
                .as("a database without the mark").isEqualTo(PendingTargetCheck.Result.refused(PendingTargetCheck.NO_COPY));

            JooqDatabaseDepartureRepository repository = new JooqDatabaseDepartureRepository(built.dsl());
            built.unitOfWork().write(() -> repository.mark(new DatabaseDeparture(TOKEN, "copy", SqlTestDatabase.START)));

            assertThat(PendingTargetCheck.check(SqlDatabaseProperties.builtIn(), dataDirectory, TOKEN).ready()).isTrue();
            assertThat(PendingTargetCheck.check(SqlDatabaseProperties.builtIn(), dataDirectory, "another-move"))
                .isEqualTo(PendingTargetCheck.Result.refused(PendingTargetCheck.NO_COPY));
            assertThat(built.dsl().fetchCount(DATABASE_DEPARTURE)).as("the check only reads").isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void anExternalTargetIsReadyOnlyWhenItIsFreeAndHoldsTheCopy(DialectTarget target) {
        DataDirectoryProperties dataDirectory = new DataDirectoryProperties(directory.toString());
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);
        assertThat(PendingTargetCheck.check(external, dataDirectory, TOKEN))
            .isEqualTo(PendingTargetCheck.Result.refused(PendingTargetCheck.NO_COPY));

        try (SqlTestDatabase holder = SqlTestDatabase.start(target, directory.resolve("holder"), external, WriteAttemptHook.NONE)) {
            JooqDatabaseDepartureRepository repository = new JooqDatabaseDepartureRepository(holder.dsl());
            holder.unitOfWork().write(() -> repository.mark(new DatabaseDeparture(TOKEN, "copy", SqlTestDatabase.START)));

            assertThat(PendingTargetCheck.check(external, dataDirectory, TOKEN))
                .isEqualTo(PendingTargetCheck.Result.retry(PendingTargetCheck.IN_USE));
        }

        assertThat(PendingTargetCheck.check(external, dataDirectory, TOKEN).ready()).isTrue();
        assertThat(PendingTargetCheck.check(external, dataDirectory, "another-move"))
            .isEqualTo(PendingTargetCheck.Result.refused(PendingTargetCheck.NO_COPY));
    }
}
