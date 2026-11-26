package gg.modl.backend.database.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.selection.DatabaseSelection.Source;
import gg.modl.backend.database.selection.DatabaseSelection.StartReport;
import gg.modl.backend.database.sql.PendingTargetCheck;
import gg.modl.backend.database.sql.PendingTargetCheck.Result;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseSelectionTest {
    private static final DatabaseConnection MYSQL = new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, "modl", false, "modl",
        "secret");
    private static final DatabaseConnection POSTGRES = new DatabaseConnection(DatabaseDialect.POSTGRESQL, "pg", 5432, "modl", false,
        "modl", "other");
    private static final String TOKEN = "move-token";
    private static final PendingTargetProbe REACHABLE = (database, token) -> TOKEN.equals(token) ? Result.READY
        : Result.refused(PendingTargetCheck.NO_COPY);
    private static final PendingTargetProbe UNREACHABLE = (database, token) -> Result.unreachable("Connection refused");
    private static final SqlDatabaseProperties CONFIGURED = new SqlDatabaseProperties("jdbc:postgresql://env:5432/modl", "envuser",
        "envpass", 10, null);

    @TempDir
    Path directory;

    private DataDirectoryProperties dataDirectory;
    private DatabaseChoiceFile file;

    @BeforeEach
    void setUp() {
        dataDirectory = new DataDirectoryProperties(directory.toString());
        file = new DatabaseChoiceFile(dataDirectory.root());
    }

    @Test
    void withoutAChoiceModlUsesTheBuiltInDatabase() {
        DatabaseSelection selection = resolve(SqlDatabaseProperties.builtIn(), REACHABLE);

        assertThat(selection.source()).isEqualTo(Source.DEFAULT);
        assertThat(selection.effective().dialect()).isEqualTo(DatabaseDialect.SQLITE);
        assertThat(selection.currentLocation()).isEqualTo(DatabaseLocation.BUILT_IN);
        assertThat(selection.managed()).isFalse();
    }

    @Test
    void thePanelChoiceIsUsedWhenNothingIsConfigured() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL).adopted());

        DatabaseSelection selection = resolve(SqlDatabaseProperties.builtIn(), UNREACHABLE);

        assertThat(selection.source()).isEqualTo(Source.PANEL);
        assertThat(selection.effective().externalUrl()).contains("jdbc:mysql://db:3306/modl");
        assertThat(selection.effective().password()).isEqualTo("secret");
        assertThat(selection.currentLocation()).isEqualTo(MYSQL.location());
        assertThat(selection.adopting()).isFalse();
    }

    @Test
    void aConfiguredDatabaseWinsAndLocksThePanel() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));

        DatabaseSelection selection = resolve(CONFIGURED, UNREACHABLE);

        assertThat(selection.source()).isEqualTo(Source.CONFIGURATION);
        assertThat(selection.managed()).isTrue();
        assertThat(selection.adopting()).isFalse();
        assertThat(selection.effective()).isSameAs(CONFIGURED);
        assertThat(selection.currentLocation()).isEqualTo(new DatabaseLocation(DatabaseDialect.POSTGRESQL, "env", 5432, "modl"));
        assertThat(file.read()).as("removing the variable later never revives the old choice").isEmpty();
        assertThat(directory.resolve(DatabaseChoiceFile.SUPERSEDED_FILE_NAME)).exists();
    }

    @Test
    void aConfiguredDatabaseIgnoresAnUnreadableChoice() throws IOException {
        Files.writeString(file.path(), "damaged");

        assertThat(resolve(CONFIGURED, REACHABLE).effective()).isSameAs(CONFIGURED);
    }

    @Test
    void anUnreadableChoiceStopsTheStartInsteadOfFallingBackToSqlite() throws IOException {
        Files.writeString(file.path(), "damaged");

        assertThatThrownBy(() -> resolve(SqlDatabaseProperties.builtIn(), REACHABLE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(DatabaseChoiceFile.FILE_NAME);
    }

    @Test
    void aPendingMoveIsAdoptedOnceItsCopyIsConfirmed() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));

        DatabaseSelection selection = resolve(SqlDatabaseProperties.builtIn(), REACHABLE);
        assertThat(selection.effective().externalUrl()).contains("jdbc:mysql://db:3306/modl");
        assertThat(selection.adopting()).isTrue();

        selection.adopt();

        PanelDatabaseChoice stored = file.read().orElseThrow();
        assertThat(stored.pending()).isFalse();
        assertThat(stored.previous()).isNull();
        assertThat(stored.moveToken()).as("a mark the copy still carries is recognized until the start commits").isEqualTo(TOKEN);
        assertThat(stored.unreported()).isEqualTo(PanelDatabaseChoice.StartEnding.ARRIVED);

        assertThat(selection.settleStart()).contains(new StartReport.Arrived(MYSQL));
        PanelDatabaseChoice settled = file.read().orElseThrow();
        assertThat(settled.moveToken()).isNull();
        assertThat(settled.unreported()).isNull();
        assertThat(selection.settleStart()).as("a start is reported once").isEmpty();
    }

    @Test
    void anArrivalThatDidNotCommitIsReportedByTheNextStart() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));
        resolve(SqlDatabaseProperties.builtIn(), REACHABLE).adopt();

        DatabaseSelection restarted = resolve(SqlDatabaseProperties.builtIn(), UNREACHABLE);

        assertThat(restarted.adopting()).isFalse();
        assertThat(restarted.effective().externalUrl()).contains("jdbc:mysql://db:3306/modl");
        assertThat(restarted.settleStart()).contains(new StartReport.Arrived(MYSQL));
    }

    @Test
    void anInterruptedWaitStopsTheStartWithoutGivingUpTheMove() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> DatabaseSelection.resolve(SqlDatabaseProperties.builtIn(), dataDirectory, UNREACHABLE,
                ArrivalWait.atStart(), new OperatorNotice()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL at db:3306/modl");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        PanelDatabaseChoice stored = file.read().orElseThrow();
        assertThat(stored.pending()).isTrue();
        assertThat(stored.selected()).isEqualTo(MYSQL);
        assertThat(stored.failure()).isNull();
    }

    @Test
    void aTargetWithoutTheCopyIsLeftAtOnceWithoutWaiting() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));
        AtomicInteger attempts = new AtomicInteger();
        PendingTargetProbe emptied = (database, token) -> {
            attempts.incrementAndGet();
            return Result.refused(PendingTargetCheck.NO_COPY);
        };

        DatabaseSelection selection = DatabaseSelection.resolve(SqlDatabaseProperties.builtIn(), dataDirectory, emptied,
            new FakeTime().waiting(), new OperatorNotice());

        assertThat(attempts).hasValue(1);
        assertThat(selection.effective().dialect()).isEqualTo(DatabaseDialect.SQLITE);
        PanelDatabaseChoice stored = file.read().orElseThrow();
        assertThat(stored.pending()).isFalse();
        assertThat(stored.moveToken()).as("the previous database recognizes its own unfinished move").isEqualTo(TOKEN);
        assertThat(stored.failure()).isNotNull();
        assertThat(stored.failure().failure().code()).isEqualTo(DatabaseSelection.COPY_MISSING_CODE);
    }

    @Test
    void aTargetLockedByAnotherServerIsLeftLikeAnUnreachableOne() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));

        DatabaseSelection selection = resolve(SqlDatabaseProperties.builtIn(), (database, token) -> Result.HELD);

        assertThat(selection.effective().dialect()).isEqualTo(DatabaseDialect.SQLITE);
        assertThat(file.read().orElseThrow().failure().failure().code()).isEqualTo(DatabaseSelection.NOT_REACHED_CODE);
        assertThat(selection.settleStart()).hasValueSatisfying(report -> assertThat(((StartReport.FellBack) report).reason())
            .isEqualTo(Result.HELD.reason()));
    }

    @Test
    void aPendingMoveWhoseCopyIsMissingAfterTheLockReturnsOnTheNextStart() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));
        DatabaseSelection selection = resolve(SqlDatabaseProperties.builtIn(), REACHABLE);

        assertThat(selection.abandonPending("gone")).isEqualTo(DatabaseConnection.builtIn());

        PanelDatabaseChoice stored = file.read().orElseThrow();
        assertThat(stored.selected()).isEqualTo(DatabaseConnection.builtIn());
        assertThat(stored.pending()).isFalse();
        assertThat(stored.failure().failure().code()).isEqualTo(DatabaseSelection.COPY_MISSING_CODE);
        assertThat(resolve(SqlDatabaseProperties.builtIn(), REACHABLE).settleStart())
            .as("the next start reports the move that did not finish")
            .contains(new StartReport.FellBack(MYSQL.location(), DatabaseConnection.builtIn(), "gone"));
    }

    @Test
    void aPendingMoveToAnUnreachableDatabaseReturnsToThePreviousOne() throws IOException {
        file.write(movedTo(movedTo(PanelDatabaseChoice.initial(), MYSQL).adopted(), POSTGRES));

        DatabaseSelection selection = resolve(SqlDatabaseProperties.builtIn(), UNREACHABLE);

        assertThat(selection.effective().externalUrl()).contains("jdbc:mysql://db:3306/modl");
        PanelDatabaseChoice stored = file.read().orElseThrow();
        assertThat(stored.selected()).isEqualTo(MYSQL);
        assertThat(stored.pending()).isFalse();
        assertThat(stored.copyingTo()).isEqualTo(POSTGRES.location());
        assertThat(stored.failure()).isNotNull();
        assertThat(stored.failure().failure().code()).isEqualTo(DatabaseSelection.NOT_REACHED_CODE);
        assertThat(stored.failure().target()).isEqualTo(POSTGRES.location());
        assertThat(selection.settleStart()).contains(new StartReport.FellBack(POSTGRES.location(), MYSQL, "Connection refused"));
        PanelDatabaseChoice settled = file.read().orElseThrow();
        assertThat(settled.moveToken()).isNull();
        assertThat(settled.copyingTo()).as("an unfinished copy stays recognizable").isEqualTo(POSTGRES.location());
        assertThat(settled.failure()).isEqualTo(stored.failure());
    }

    @Test
    void aDatabaseThatAnswersWithinTheBudgetIsKept() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));
        AtomicInteger attempts = new AtomicInteger();
        PendingTargetProbe lateStart = (database, token) -> attempts.incrementAndGet() == 1 ? Result.unreachable("starting") : Result.READY;

        FakeTime time = new FakeTime();

        DatabaseSelection selection = DatabaseSelection.resolve(SqlDatabaseProperties.builtIn(), dataDirectory, lateStart,
            time.waiting(), new OperatorNotice());

        assertThat(attempts).hasValue(2);
        assertThat(time.elapsed()).isEqualTo(ArrivalWait.RETRY);
        assertThat(file.read().orElseThrow().pending()).isTrue();
        assertThat(selection.effective().externalUrl()).contains("jdbc:mysql://db:3306/modl");
    }

    @Test
    void theCurrentDatabaseStaysTheOneThisStartOpened() throws IOException {
        DatabaseSelection selection = resolve(SqlDatabaseProperties.builtIn(), REACHABLE);

        selection.recordCopying(MYSQL.location(), TOKEN);
        selection.recordMoved(MYSQL);

        assertThat(selection.currentLocation()).isEqualTo(DatabaseLocation.BUILT_IN);
        assertThat(file.read().orElseThrow().selected()).isEqualTo(MYSQL);
    }

    @Test
    void anAdoptedDatabaseIsNeverAbandonedWhenItIsUnreachable() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL).adopted().reported());

        DatabaseSelection selection = resolve(SqlDatabaseProperties.builtIn(), UNREACHABLE);

        assertThat(selection.effective().externalUrl()).contains("jdbc:mysql://db:3306/modl");
        assertThat(selection.settleStart()).isEmpty();
    }

    @Test
    void aConfiguredDatabaseNeverWritesTheChoiceAtTheEndOfTheStart() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL).adopted());

        assertThat(resolve(CONFIGURED, REACHABLE).settleStart()).isEmpty();
        assertThat(file.read()).isEmpty();
    }

    @Test
    void theCommandLineToolsFollowTheSameChoiceWithoutChangingIt() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL).adopted());

        assertThat(DatabaseSelection.forTools(SqlDatabaseProperties.builtIn(), dataDirectory).externalUrl())
            .contains("jdbc:mysql://db:3306/modl");
        assertThat(DatabaseSelection.forTools(CONFIGURED, dataDirectory)).isSameAs(CONFIGURED);
        assertThat(file.read().orElseThrow().unreported()).isEqualTo(PanelDatabaseChoice.StartEnding.ARRIVED);
    }

    @Test
    void theCommandLineToolsRefuseWhileAMoveWaitsForTheRestart() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));

        assertThatThrownBy(() -> DatabaseSelection.forTools(SqlDatabaseProperties.builtIn(), dataDirectory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Start modl once");
        assertThat(DatabaseSelection.forTools(CONFIGURED, dataDirectory)).isSameAs(CONFIGURED);
        assertThat(file.read().orElseThrow().pending()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 110, 120})
    void aLockLeftByACrashedServerIsAwaitedUntilItsSessionTimesOut(int releasedAfterSeconds) throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));
        FakeTime time = new FakeTime();
        Duration released = Duration.ofSeconds(releasedAfterSeconds);
        PendingTargetProbe heldUntilReleased = (database, token) -> time.elapsed().compareTo(released) < 0 ? Result.HELD : Result.READY;

        DatabaseSelection selection = DatabaseSelection.resolve(SqlDatabaseProperties.builtIn(), dataDirectory, heldUntilReleased,
            time.waiting(), new OperatorNotice());

        assertThat(selection.adopting()).isTrue();
        assertThat(selection.effective().externalUrl()).contains("jdbc:mysql://db:3306/modl");
        assertThat(file.read().orElseThrow().failure()).isNull();
        assertThat(time.elapsed()).isLessThan(released.plus(ArrivalWait.RETRY));
    }

    @ParameterizedTest
    @EnumSource(value = PendingTargetCheck.Outcome.class, names = {"UNREACHABLE", "HELD"})
    void theStartWaitIsBoundedByTheBudgetOfTheOutcome(PendingTargetCheck.Outcome outcome) throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));
        FakeTime time = new FakeTime();
        AtomicInteger attempts = new AtomicInteger();
        PendingTargetProbe neverReady = (database, token) -> {
            attempts.incrementAndGet();
            return new Result(outcome, "waiting");
        };

        DatabaseSelection selection = DatabaseSelection.resolve(SqlDatabaseProperties.builtIn(), dataDirectory, neverReady,
            time.waiting(), new OperatorNotice());

        assertThat(time.elapsed()).isEqualTo(outcome.waitBudget());
        assertThat(attempts).hasValue((int) (outcome.waitBudget().toSeconds() / ArrivalWait.RETRY.toSeconds()) + 1);
        assertThat(selection.effective().dialect()).isEqualTo(DatabaseDialect.SQLITE);
        assertThat(file.read().orElseThrow().failure().failure().code()).isEqualTo(DatabaseSelection.NOT_REACHED_CODE);
    }

    @Test
    void anUnreachableTargetStopsWaitingAtItsOwnBudgetEvenAfterALockWasHeld() throws IOException {
        file.write(movedTo(PanelDatabaseChoice.initial(), MYSQL));
        FakeTime time = new FakeTime();
        PendingTargetProbe heldThenGone = (database, token) -> time.elapsed().compareTo(Duration.ofSeconds(100)) < 0 ? Result.HELD
            : Result.unreachable("Connection refused");

        DatabaseSelection selection = DatabaseSelection.resolve(SqlDatabaseProperties.builtIn(), dataDirectory, heldThenGone,
            time.waiting(), new OperatorNotice());

        assertThat(time.elapsed()).isEqualTo(Duration.ofSeconds(100));
        assertThat(selection.settleStart()).hasValueSatisfying(report -> assertThat(((StartReport.FellBack) report).reason())
            .isEqualTo("Connection refused"));
    }

    private DatabaseSelection resolve(SqlDatabaseProperties configured, PendingTargetProbe probe) {
        return DatabaseSelection.resolve(configured, dataDirectory, probe, new FakeTime().waiting(), new OperatorNotice());
    }

    private static final class FakeTime {
        private final AtomicLong nanos = new AtomicLong(TimeUnit.DAYS.toNanos(1));
        private final long start = nanos.get();

        ArrivalWait waiting() {
            return new ArrivalWait(nanos::get, pause -> nanos.addAndGet(pause.toNanos()));
        }

        Duration elapsed() {
            return Duration.ofNanos(nanos.get() - start);
        }
    }

    private static PanelDatabaseChoice movedTo(PanelDatabaseChoice from, DatabaseConnection target) {
        return from.copying(target.location(), TOKEN).movedTo(target);
    }
}
