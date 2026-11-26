package gg.modl.backend.database.selection;

import static gg.modl.backend.database.jooq.Tables.DATABASE_DEPARTURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.DatabaseDeparture;
import gg.modl.backend.database.sql.PendingTargetCheck;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.databasemove.repository.JooqDatabaseDepartureRepository;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DepartureGuardTest {
    private static final String TOKEN = "move-token";
    private static final DatabaseConnection MYSQL = new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, "modl", false, "modl",
        "secret");

    @TempDir
    Path directory;

    private DataDirectoryProperties dataDirectory;
    private DatabaseChoiceFile file;
    private SqlTestDatabase database;

    @BeforeEach
    void setUp() {
        dataDirectory = new DataDirectoryProperties(directory.toString());
        file = new DatabaseChoiceFile(dataDirectory.root());
        database = SqlTestDatabase.start(TestDialects.SQLITE, directory);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void aDatabaseThatWasMovedAwayRefusesToStart() {
        mark("other-move");

        assertThatThrownBy(() -> guard(selection(), null).beforeMigration(flyway()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MySQL at db:3306/modl")
            .hasMessageContaining(DepartureGuard.REOPEN_SWITCH + "=other-move");
        assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isEqualTo(1);
    }

    @Test
    void theReopenSwitchStartsTheOldCopyOfThatMoveOnly() {
        mark("other-move");

        assertThatThrownBy(() -> guard(selection(), "older-move").beforeMigration(flyway())).isInstanceOf(IllegalStateException.class);
        guard(selection(), "other-move").beforeMigration(flyway());

        assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isZero();
    }

    @Test
    void theMarkOfAMoveThatDidNotFinishIsRemoved() throws IOException {
        file.write(PanelDatabaseChoice.initial().copying(MYSQL.location(), TOKEN));
        mark(TOKEN);

        guard(selection(), null).beforeMigration(flyway());

        assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isZero();
    }

    @Test
    void aPendingMoveIsAdoptedWhenTheDatabaseHoldsItsCopy() throws IOException {
        file.write(PanelDatabaseChoice.initial().copying(MYSQL.location(), TOKEN).movedTo(MYSQL));
        mark(TOKEN);
        DatabaseSelection selection = selection();

        guard(selection, null).beforeMigration(flyway());

        assertThat(file.read().orElseThrow().pending()).isFalse();
        assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isZero();
        assertThat(selection.settleStart()).contains(new DatabaseSelection.StartReport.Arrived(MYSQL));
    }

    @Test
    void aStartThatStoppedBetweenAdoptingAndClearingTheMarkFinishesTheArrivalNextTime() throws IOException {
        file.write(PanelDatabaseChoice.initial().copying(MYSQL.location(), TOKEN).movedTo(MYSQL).adopted());
        mark(TOKEN);
        DatabaseSelection restarted = selection();

        guard(restarted, null).beforeMigration(flyway());

        assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isZero();
        assertThat(restarted.settleStart()).contains(new DatabaseSelection.StartReport.Arrived(MYSQL));
        assertThat(file.read().orElseThrow().moveToken()).isNull();
    }

    @Test
    void aMarkLeftAfterTheArrivalWasSettledRefusesTheStart() throws IOException {
        file.write(PanelDatabaseChoice.initial().copying(MYSQL.location(), TOKEN).movedTo(MYSQL).adopted().reported());
        mark(TOKEN);

        assertThatThrownBy(() -> guard(selection(), null).beforeMigration(flyway()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(DepartureGuard.REOPEN_SWITCH + "=" + TOKEN);
    }

    @Test
    void aPendingMoveOntoADatabaseWithoutItsCopyStopsAndReturnsNextTime() throws IOException {
        file.write(PanelDatabaseChoice.initial().copying(MYSQL.location(), TOKEN).movedTo(MYSQL));
        DatabaseSelection selection = selection();

        assertThatThrownBy(() -> guard(selection, null).beforeMigration(flyway()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not hold the copy modl made");

        PanelDatabaseChoice stored = file.read().orElseThrow();
        assertThat(stored.pending()).isFalse();
        assertThat(stored.selected()).isEqualTo(DatabaseConnection.builtIn());
        assertThat(selection().settleStart()).as("the next start records why the move did not finish")
            .contains(new DatabaseSelection.StartReport.FellBack(MYSQL.location(), DatabaseConnection.builtIn(), PendingTargetCheck.NO_COPY));
    }

    @Test
    void anUnmarkedDatabaseStartsAsBefore() {
        guard(selection(), null).beforeMigration(flyway());

        assertThat(database.dsl().fetchCount(DATABASE_DEPARTURE)).isZero();
    }

    private void mark(String token) {
        JooqDatabaseDepartureRepository repository = new JooqDatabaseDepartureRepository(database.dsl());
        database.unitOfWork().write(() -> repository.mark(new DatabaseDeparture(token, MYSQL.describe(), SqlTestDatabase.START)));
    }

    private DatabaseSelection selection() {
        return DatabaseSelection.resolve(SqlDatabaseProperties.builtIn(), dataDirectory,
            (target, token) -> PendingTargetCheck.Result.READY, ArrivalWait.atStart(), new OperatorNotice());
    }

    private DepartureGuard guard(DatabaseSelection selection, @Nullable String reopenedMove) {
        return new DepartureGuard(selection, DatabaseDialect.SQLITE, reopenedMove, new OperatorNotice());
    }

    private Flyway flyway() {
        return database.bean(Flyway.class);
    }
}
