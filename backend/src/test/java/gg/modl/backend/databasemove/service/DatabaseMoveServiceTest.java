package gg.modl.backend.databasemove.service;

import static gg.modl.backend.database.jooq.Tables.DATABASE_DEPARTURE;
import static gg.modl.backend.database.jooq.Tables.INSTANCE_SECRETS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.WritePause;
import gg.modl.backend.database.backup.ArchivingDatabaseBackup;
import gg.modl.backend.database.backup.BackupTestData;
import gg.modl.backend.database.backup.DatabaseCopy;
import gg.modl.backend.database.backup.RowCountMismatch;
import gg.modl.backend.database.selection.ArrivalWait;
import gg.modl.backend.database.selection.DatabaseChoiceFile;
import gg.modl.backend.database.selection.DatabaseConnection;
import gg.modl.backend.database.selection.DatabaseSelection;
import gg.modl.backend.database.selection.MoveFailure;
import gg.modl.backend.database.selection.PanelDatabaseChoice;
import gg.modl.backend.database.sql.DatabaseDeparture;
import gg.modl.backend.database.sql.OfflineDatabase;
import gg.modl.backend.database.sql.PendingTargetCheck;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.databasemove.repository.DatabaseDepartureRepository;
import gg.modl.backend.databasemove.repository.JooqDatabaseDepartureRepository;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceSecretsService;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.migration.service.ConcurrentImport;
import gg.modl.backend.migration.service.ImportKind;
import gg.modl.backend.migration.service.TestImports;
import gg.modl.backend.migration.service.ImportExclusion;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.OldCopyRemovals;
import gg.modl.backend.storage.StorageMoves;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.ExternalDialectTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import gg.modl.backend.update.install.InstanceRestart;
import gg.modl.backend.update.install.InstanceRestart.Mode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseMoveServiceTest {
    private static final String KEY = "storage-signing-key";
    private static final String OWNER = "owner@example.com";
    private static final Duration SETTLED = Duration.ofSeconds(60);

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final InstanceRestart restart = mock(InstanceRestart.class);
    private final PanelActionAuditor auditor = mock(PanelActionAuditor.class);
    private final StorageMoves storageMoves = mock(StorageMoves.class);
    private final OldCopyRemovals oldCopyRemovals = mock(OldCopyRemovals.class);
    private final LogService logService = mock(LogService.class);
    private final ImportExclusion importExclusion = new ImportExclusion();
    private final RealtimeEventPublisher realtime = mock(RealtimeEventPublisher.class);
    private final List<ConcurrentImport> imports = new ArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();
    private DataDirectoryProperties dataDirectory;
    private SqlTestDatabase live;
    private DatabaseDepartureRepository departures;
    private DatabaseSelection selection;
    private DatabaseMoveService service;

    @BeforeEach
    void setUp() {
        dataDirectory = new DataDirectoryProperties(directory.toString());
        live = SqlTestDatabase.start(TestDialects.SQLITE, directory);
        opened.add(live);
        departures = new JooqDatabaseDepartureRepository(live.dsl());
        BackupTestData.seed(live);
        live.unitOfWork().write(() -> {
            live.dsl().insertInto(INSTANCE_SECRETS)
                .set(INSTANCE_SECRETS.ID, 1)
                .set(INSTANCE_SECRETS.AUTH_CODE_HASH_SECRET, "auth-secret")
                .set(INSTANCE_SECRETS.STORAGE_SIGNING_KEY, KEY)
                .set(INSTANCE_SECRETS.CREATED_AT, SqlTestDatabase.START)
                .execute();
        });
        when(restart.mode()).thenReturn(Mode.IN_PLACE);
        service = service(SqlDatabaseProperties.builtIn());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.stop();
        }
        writable(directory);
        for (AutoCloseable resource : opened.reversed()) {
            resource.close();
        }
    }

    @ExternalDialectTest
    void aMoveCopiesEverythingKeepsWritesPausedAndRestartsOntoTheNewDatabase(DialectTarget target) throws Exception {
        DatabaseConnection connection = connectionTo(TestDialects.freshDatabase(target), target.dialect());

        assertThat(service.test(connection).ok()).isTrue();
        service.move(server, OWNER, connection, true);
        DatabaseMove finished = settled();

        assertThat(finished.phase()).isEqualTo(DatabaseMovePhase.RESTARTING);
        assertThat(finished.copiedRows()).isPositive();
        assertThat(service.isMovingDatabase()).isTrue();
        verify(restart, timeout(SETTLED.toMillis())).restart();
        verify(auditor).recordStaffAction(eq(server), eq(OWNER), contains("Started moving the database"));
        PanelDatabaseChoice stored = new DatabaseChoiceFile(dataDirectory.root()).read().orElseThrow();
        assertThat(stored.selected()).isEqualTo(connection);
        assertThat(stored.pending()).isTrue();
        assertThat(stored.previous()).isEqualTo(DatabaseConnection.builtIn());
        assertThatThrownBy(() -> live.unitOfWork().write(() -> live.dsl().deleteFrom(PLAYERS).execute()))
            .isInstanceOfSatisfying(DatabaseUnavailableException.class, refused -> assertThat(refused.code()).isEqualTo("DATABASE_MOVING"));
        assertThat(live.dsl().fetchCount(PLAYERS)).isEqualTo(BackupTestData.PLAYER_COUNT);
        assertThat(live.dsl().selectFrom(DATABASE_DEPARTURE).fetchSingle().getMoveToken()).isEqualTo(stored.moveToken());
        assertThat(PendingTargetCheck.check(connection.properties(SqlDatabaseProperties.builtIn()), dataDirectory,
            stored.moveToken()).outcome()).as("the target lock is held until modl restarts").isEqualTo(PendingTargetCheck.Outcome.HELD);
        service.stop();
        assertThat(PendingTargetCheck.check(connection.properties(SqlDatabaseProperties.builtIn()), dataDirectory,
            stored.moveToken()).outcome()).as("the copy carries the move token").isEqualTo(PendingTargetCheck.Outcome.READY);
    }

    @ExternalDialectTest
    void aMoveThatCannotBeRecordedResumesWritesAndRemovesItsMark(DialectTarget target) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        DatabaseConnection connection = connectionTo(TestDialects.freshDatabase(target), target.dialect());
        DatabaseDepartureRepository marking = departures;
        departures = new DatabaseDepartureRepository() {
            @Override
            public void mark(DatabaseDeparture departure) {
                marking.mark(departure);
                readOnly(directory);
            }

            @Override
            public void clear() {
                marking.clear();
            }
        };
        service.stop();
        service = service(SqlDatabaseProperties.builtIn());
        Files.createDirectories(dataDirectory.temporary());

        service.move(server, OWNER, connection, true);
        DatabaseMove failed = settled();

        assertThat(failed.phase()).isEqualTo(DatabaseMovePhase.FAILED);
        assertThat(failed.errorCode()).isEqualTo(DatabaseMoveCodes.COPY_FAILED);
        assertThat(failed.error()).isEqualTo("The new database could not be recorded");
        assertThat(writesAdmitted()).isTrue();
        assertThat(live.dsl().fetchCount(DATABASE_DEPARTURE)).isZero();
        String token = new DatabaseChoiceFile(dataDirectory.root()).read().orElseThrow().moveToken();
        assertThat(PendingTargetCheck.check(connection.properties(SqlDatabaseProperties.builtIn()), dataDirectory, token))
            .as("a choice that reached the disk anyway cannot adopt the copy").isEqualTo(PendingTargetCheck.Result.refused(
                PendingTargetCheck.NO_COPY));
        verify(restart, never()).restart();
    }

    @ExternalDialectTest
    void aCopyThatCannotBeToldApartFromAFinishedMoveKeepsWritesPaused(DialectTarget target) throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        SqlDatabaseProperties external = TestDialects.freshDatabase(target);
        DatabaseConnection connection = connectionTo(external, target.dialect());
        DatabaseDepartureRepository marking = departures;
        departures = new DatabaseDepartureRepository() {
            @Override
            public void mark(DatabaseDeparture departure) {
                marking.mark(departure);
                readOnly(directory);
            }

            @Override
            public void clear() {
                marking.clear();
            }
        };
        AtomicBoolean refusing = new AtomicBoolean();
        doAnswer(invocation -> {
            if (service.view().move().filter(move -> move.copiedRows() > 0).isPresent() && refusing.compareAndSet(false, true)) {
                refuseDepartureDeletes(target, external);
            }
            return null;
        }).when(realtime).invalidatePanel(any(), any());
        service.stop();
        service = service(SqlDatabaseProperties.builtIn());
        Files.createDirectories(dataDirectory.temporary());

        service.move(server, OWNER, connection, true);

        assertThat(settled().phase()).isEqualTo(DatabaseMovePhase.WAITING_FOR_RESTART);
        assertThat(writesAdmitted()).isFalse();
        assertThat(service.isMovingDatabase()).isTrue();
        verify(restart, never()).restart();
    }

    @Test
    void theFirstCommittedStartReportsTheArrivalOnceAndSettlesTheChoice() throws IOException {
        DatabaseConnection target = new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, "modl", false, "modl", null);
        DatabaseChoiceFile file = new DatabaseChoiceFile(dataDirectory.root());
        file.write(PanelDatabaseChoice.initial().copying(target.location(), "move").movedTo(target).adopted());
        service.stop();
        service = service(SqlDatabaseProperties.builtIn());

        service.reportStart();
        service.reportStart();

        verify(logService).recordStaffAction(any(), any(), contains("Moved the database to MySQL"));
        assertThat(file.read().orElseThrow().settled()).isTrue();
    }

    private static void refuseDepartureDeletes(DialectTarget target, SqlDatabaseProperties external) {
        String table = DATABASE_DEPARTURE.getName();
        List<String> statements = target.dialect() == DatabaseDialect.POSTGRESQL
            ? List.of("CREATE FUNCTION modl_test_refuse() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'refused'; END $$ LANGUAGE plpgsql",
                "CREATE TRIGGER modl_test_refuse BEFORE DELETE ON " + table + " FOR EACH ROW EXECUTE FUNCTION modl_test_refuse()")
            : List.of("CREATE TRIGGER modl_test_refuse BEFORE DELETE ON " + table
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refused'");
        statements.forEach(sql -> TestDialects.executeIn(target, external, sql));
    }

    @Test
    void aCopyMissingRowsNamesTheFirstTables() {
        List<RowCountMismatch> mismatches = List.of(new RowCountMismatch("a", 1, 0), new RowCountMismatch("b", 2, 1),
            new RowCountMismatch("c", 3, 2), new RowCountMismatch("d", 4, 3), new RowCountMismatch("e", 5, 4));

        MoveFailure failure = DatabaseMoveService.verify(mismatches).orElseThrow();

        assertThat(failure.code()).isEqualTo(DatabaseMoveCodes.COPY_FAILED);
        assertThat(failure.message()).isEqualTo("The copy does not hold every row");
        assertThat(failure.detail()).startsWith(new RowCountMismatch("a", 1, 0).describe()).endsWith(" and 2 more tables");
        assertThat(DatabaseMoveService.verify(List.of())).isEmpty();
    }

    @Test
    void aManualRestartMustBeAcknowledged() {
        when(restart.mode()).thenReturn(Mode.MANUAL);
        DatabaseConnection target = new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, "modl", false, "modl", null);

        assertThatThrownBy(() -> service.move(server, OWNER, target, false))
            .isInstanceOfSatisfying(ValidationException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo(DatabaseMoveCodes.RESTART_NOT_ACKNOWLEDGED));
        assertThat(service.isMovingDatabase()).isFalse();
    }

    @ExternalDialectTest
    void withoutAnAutomaticRestartTheMoveWaitsForTheOperator(DialectTarget target) throws InterruptedException {
        when(restart.mode()).thenReturn(Mode.MANUAL);
        DatabaseConnection connection = connectionTo(TestDialects.freshDatabase(target), target.dialect());

        service.move(server, OWNER, connection, true);

        assertThat(settled().phase()).isEqualTo(DatabaseMovePhase.WAITING_FOR_RESTART);
        verify(restart, never()).restart();
        assertThat(writesAdmitted()).isFalse();
        assertThatThrownBy(() -> service.move(server, OWNER, connection, true))
            .isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.getMachineCode()).isEqualTo("DATABASE_MOVE_RUNNING"));
    }

    @ExternalDialectTest
    void aContainerExitsOnlyAfterTheNewDatabaseIsRecordedWithWritesPaused(DialectTarget target) throws Exception {
        when(restart.mode()).thenReturn(Mode.EXIT);
        DatabaseConnection connection = connectionTo(TestDialects.freshDatabase(target), target.dialect());
        List<Boolean> recordedAtExit = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            PanelDatabaseChoice stored = new DatabaseChoiceFile(dataDirectory.root()).read().orElseThrow();
            recordedAtExit.add(stored.pending() && stored.selected().equals(connection) && !writesAdmitted());
            return null;
        }).when(restart).restart();

        service.move(server, OWNER, connection, true);

        assertThat(settled().phase()).isEqualTo(DatabaseMovePhase.RESTARTING);
        verify(restart, timeout(SETTLED.toMillis())).restart();
        assertThat(recordedAtExit).containsExactly(true);
        assertThat(service.view().restartMode()).isEqualTo(Mode.EXIT);
    }

    @Test
    void aTargetThatCannotBeReachedFailsWithoutPausingAnything() throws InterruptedException {
        DatabaseConnection closed = new DatabaseConnection(DatabaseDialect.POSTGRESQL, "127.0.0.1", 1, "modl", false, "modl", "secret");
        live.unitOfWork().write(() -> departures.mark(new DatabaseDeparture("earlier-attempt", "PostgreSQL at 127.0.0.1:1/modl",
            SqlTestDatabase.START)));

        assertThat(service.test(closed).code()).isEqualTo(DatabaseMoveCodes.UNREACHABLE);
        service.move(server, OWNER, closed, true);
        DatabaseMove failed = settled();

        assertThat(failed.phase()).isEqualTo(DatabaseMovePhase.FAILED);
        assertThat(failed.errorCode()).isEqualTo(DatabaseMoveCodes.UNREACHABLE);
        assertThat(service.isMovingDatabase()).isFalse();
        assertThat(writesAdmitted()).isTrue();
        verify(restart, never()).restart();
        verify(auditor).recordStaffAction(eq(server), eq(OWNER), contains("failed"));
        assertThat(live.dsl().fetchCount(DATABASE_DEPARTURE)).as("a mark left by an earlier attempt is removed").isZero();
    }

    @Test
    void startingAMoveIsAuditedWithItsEndsAndRestartOutsideTheServiceLocks() throws InterruptedException {
        when(restart.mode()).thenReturn(Mode.EXIT);
        DatabaseConnection closed = new DatabaseConnection(DatabaseDialect.POSTGRESQL, "127.0.0.1", 1, "modl", false, "modl", "secret");
        List<Boolean> auditedOutsideLocks = new ArrayList<>();
        doAnswer(invocation -> {
            boolean exclusionFree = CompletableFuture.supplyAsync(() -> importExclusion.exclusively(() -> true))
                .completeOnTimeout(false, 5, TimeUnit.SECONDS).join();
            auditedOutsideLocks.add(!Thread.holdsLock(service) && exclusionFree && service.isMovingDatabase());
            return null;
        }).when(auditor).recordStaffAction(eq(server), eq(OWNER), contains("Started"));

        service.move(server, OWNER, closed, true);
        settled();

        verify(auditor).recordStaffAction(server, OWNER, "Started moving the database from SQLite to PostgreSQL; restart: container restart");
        assertThat(auditedOutsideLocks).containsExactly(true);
    }

    @Test
    void aMoveWhoseStartCannotBeAuditedIsNotStarted() {
        DatabaseConnection closed = new DatabaseConnection(DatabaseDialect.POSTGRESQL, "127.0.0.1", 1, "modl", false, "modl", "secret");
        doAnswer(invocation -> {
            throw new IllegalStateException("audit log unavailable");
        }).when(auditor).recordStaffAction(eq(server), eq(OWNER), contains("Started"));

        assertThatThrownBy(() -> service.move(server, OWNER, closed, true)).hasMessage("audit log unavailable");

        assertThat(service.isMovingDatabase()).isFalse();
        assertThat(service.view().move()).isEmpty();
        assertThat(writesAdmitted()).isTrue();
    }

    @Test
    void theCurrentDatabaseIsNotATarget() {
        assertThat(service.test(DatabaseConnection.builtIn()).code()).isEqualTo(DatabaseMoveCodes.SAME);
        assertThatThrownBy(() -> service.move(server, OWNER, DatabaseConnection.builtIn(), true))
            .isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.getMachineCode()).isEqualTo("DATABASE_SAME"));
    }

    @Test
    void aRunningImportOrFileMoveBlocksTheMove() {
        DatabaseConnection target = new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, "modl", false, "modl", null);
        imports.add(TestImports.of(ImportKind.PLUGIN, () -> true));

        assertThatThrownBy(() -> service.move(server, OWNER, target, true))
            .isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.getMachineCode()).isEqualTo("DATABASE_IMPORT_RUNNING"));

        imports.clear();
        when(storageMoves.isMovingFiles()).thenReturn(true);
        assertThatThrownBy(() -> service.move(server, OWNER, target, true))
            .isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.getMachineCode()).isEqualTo("DATABASE_STORAGE_MOVING"));

        when(storageMoves.isMovingFiles()).thenReturn(false);
        when(oldCopyRemovals.isRemovingOldCopies()).thenReturn(true);
        assertThatThrownBy(() -> service.move(server, OWNER, target, true))
            .isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.getMachineCode()).isEqualTo("DATABASE_STORAGE_MOVING"));
        verify(auditor, never()).recordStaffAction(any(), anyString(), anyString());
    }

    @Test
    void aConfiguredDatabaseLocksThePanel() {
        service.stop();
        service = service(new SqlDatabaseProperties("jdbc:mysql://env:3306/modl", "modl", "secret", null, null));
        DatabaseConnection target = new DatabaseConnection(DatabaseDialect.MYSQL, "db", 3306, "modl", false, "modl", null);

        assertThat(service.view().managed()).isTrue();
        assertThatThrownBy(() -> service.test(target))
            .isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.getMachineCode()).isEqualTo("DATABASE_MANAGED_BY_CONFIG"));
    }

    private DatabaseMoveService service(SqlDatabaseProperties configured) {
        selection = DatabaseSelection.resolve(configured, dataDirectory, (database, token) -> PendingTargetCheck.Result.READY,
            ArrivalWait.atStart(), new OperatorNotice());
        InstanceSecretsService secrets = mock(InstanceSecretsService.class);
        when(secrets.storageSigningKey()).thenReturn(KEY);
        OfflineDatabase source = OfflineDatabase.open(SqlDatabaseProperties.builtIn(), dataDirectory);
        opened.add(source);
        ArchivingDatabaseBackup backup = new ArchivingDatabaseBackup(source.source(), dataDirectory.temporary(), BackupDiskSpace.SYSTEM,
            Clock.systemUTC());
        DatabaseCopy copy = new DatabaseCopy(backup, dataDirectory.temporary(), BackupDiskSpace.SYSTEM, Clock.systemUTC());
        return new DatabaseMoveService(selection, new DatabaseTargets(selection, dataDirectory, secrets), copy, departures,
            live.unitOfWork(), live.bean(WritePause.class), restart, importExclusion, TestImports.running(() -> imports), storageMoves,
            oldCopyRemovals, auditor, logService, mock(InstanceContext.class), realtime,
            new OperatorNotice(), Clock.systemUTC());
    }

    private DatabaseMove settled() throws InterruptedException {
        long deadline = System.nanoTime() + SETTLED.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<DatabaseMove> move = service.view().move().filter(DatabaseMoveServiceTest::settledPhase);
            if (move.isPresent()) {
                return move.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The database move did not settle within " + SETTLED);
    }

    private static boolean settledPhase(DatabaseMove move) {
        return move.phase() == DatabaseMovePhase.FAILED || move.phase() == DatabaseMovePhase.RESTARTING
            || move.phase() == DatabaseMovePhase.WAITING_FOR_RESTART;
    }

    private static DatabaseConnection connectionTo(SqlDatabaseProperties external, DatabaseDialect dialect) {
        URI address = URI.create(external.externalUrl().orElseThrow().substring("jdbc:".length()));
        return new DatabaseConnection(dialect, address.getHost(), address.getPort(), address.getPath().substring(1), false,
            external.username(), external.password());
    }

    private static void readOnly(Path folder) {
        try {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writable(Path folder) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private boolean writesAdmitted() {
        try {
            live.unitOfWork().write(() -> {
            });
            return true;
        } catch (DatabaseUnavailableException refused) {
            return false;
        }
    }
}
