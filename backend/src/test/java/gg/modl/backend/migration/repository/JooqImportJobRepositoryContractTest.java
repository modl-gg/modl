package gg.modl.backend.migration.repository;

import static gg.modl.backend.database.jooq.Tables.IMPORT_JOBS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.migration.data.ExportCounts;
import gg.modl.backend.migration.data.ImportEnd;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.migration.data.IpHistoryRetention;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.data.ScopedPunishments;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;

class JooqImportJobRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aJobRoundTripsWithEveryColumn(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqImportJobRepository repository = new JooqImportJobRepository(database.dsl());
            MigrationStatus job = MigrationStatus.builder()
                .id("job")
                .type("litebans")
                .status(ImportState.FAILED)
                .recordsProcessed(10)
                .recordsSkipped(2)
                .totalRecords(50)
                .exportFailed(3)
                .exportSkipped(4)
                .startedFrom("Proxy ü")
                .startedAddress("2001:db8::1")
                .scopedPunishments(ScopedPunishments.WIDEN)
                .ipHistory(IpHistoryRetention.KEEP)
                .ending(ImportEnding.EXPORT_FAILED)
                .error("broken")
                .startedAt(NOW)
                .lastProgressAt(NOW.plusSeconds(4))
                .completedAt(NOW.plusSeconds(5))
                .build();

            database.unitOfWork().write(() -> repository.insert(job));

            assertThat(repository.findById("job")).contains(job);
            assertThat(repository.findLatest()).contains(job);
        }
    }

    @SqlContractTest
    void theLatestJobHasTheNewestStartThenTheHighestId(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqImportJobRepository repository = new JooqImportJobRepository(database.dsl());
            assertThat(repository.findLatest()).isEmpty();
            database.unitOfWork().write(() -> {
                repository.insert(job("never-started", ImportState.BUILDING, null));
                repository.insert(job("old", ImportState.BUILDING, NOW.minusSeconds(10)));
                repository.insert(job("a", ImportState.PROCESSING, NOW));
                repository.insert(job("b", ImportState.PROCESSING, NOW));
            });

            assertThat(repository.findLatest()).get().extracting(MigrationStatus::getId).isEqualTo("b");
            assertThat(repository.findActive()).get().extracting(MigrationStatus::getId).isEqualTo("b");
        }
    }

    @SqlContractTest
    void theFourRunningStatusesAreActiveAndTheEndedOnesAreNot(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqImportJobRepository repository = new JooqImportJobRepository(database.dsl());
            for (ImportState state : ImportState.values()) {
                database.reset();
                database.unitOfWork().write(() -> repository.insert(job("job", state, NOW)));

                assertThat(repository.findActive().isPresent()).as(state.name()).isEqualTo(state.isActive());
            }
        }
    }

    @SqlContractTest
    void everyGuardedUpdateChangesOnlyAJobInItsExpectedStatus(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqImportJobRepository repository = new JooqImportJobRepository(database.dsl());
            for (ImportState state : ImportState.values()) {
                database.reset();
                MigrationStatus stored = job("job", state, NOW);
                database.unitOfWork().write(() -> repository.insert(stored));
                Instant later = NOW.plusSeconds(1);

                if (state != ImportState.UPLOADING) {
                    assertThat(write(database,
                        () -> repository.transition("job", ImportState.UPLOADING, ImportState.RECEIVING, later))).isFalse();
                }
                if (state != ImportState.BUILDING) {
                    assertThat(write(database, () -> repository.recordExport("job", ImportState.UPLOADING,
                        new ExportCounts(1, 2, 0, 0), later))).isFalse();
                }
                if (state != ImportState.RECEIVING) {
                    assertThat(write(database, () -> repository.beginProcessing("job", ImportState.RECEIVING, later))).isFalse();
                }
                if (state != ImportState.PROCESSING) {
                    ImportedCounts counts = new ImportedCounts(9, 0, 9);
                    assertThat(write(database, () -> repository.recordProcessed("job", counts, later))).isFalse();
                    assertThat(write(database, () -> repository.complete("job", counts, later))).isFalse();
                }
                if (!state.isActive()) {
                    assertThat(write(database, () -> repository.end("job", ImportState.ACTIVE,
                        new ImportEnd(ImportEnding.CANCELLED, "stop", later)))).isFalse();
                }

                assertThat(repository.findById("job")).contains(stored);
            }
        }
    }

    @SqlContractTest
    void theUpdatesMoveAJobThroughItsWholeLife(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqImportJobRepository repository = new JooqImportJobRepository(database.dsl());
            database.unitOfWork().write(() -> repository.insert(job("job", ImportState.BUILDING, NOW)));

            assertThat(write(database, () -> repository.recordExport("job", ImportState.UPLOADING,
                new ExportCounts(8, 10, 1, 4), NOW.plusSeconds(1)))).isTrue();
            assertThat(write(database, () -> repository.transition("job", ImportState.UPLOADING, ImportState.RECEIVING,
                NOW.plusSeconds(2)))).isTrue();
            assertThat(write(database, () -> repository.beginProcessing("job", ImportState.RECEIVING, NOW.plusSeconds(3))))
                .isTrue();
            assertThat(repository.findById("job")).get().satisfies(processing -> {
                assertThat(processing.getRecordsProcessed()).isZero();
                assertThat(processing.getTotalRecords()).isEqualTo(10);
            });
            assertThat(write(database, () -> repository.complete("job", new ImportedCounts(7, 1, null), NOW.plusSeconds(4))))
                .isTrue();

            assertThat(repository.findById("job")).get().satisfies(completed -> {
                assertThat(completed.getStatus()).isEqualTo(ImportState.COMPLETED);
                assertThat(completed.getRecordsProcessed()).isEqualTo(7);
                assertThat(completed.getTotalRecords()).isEqualTo(10);
                assertThat(completed.getExportFailed()).isEqualTo(1);
                assertThat(completed.getExportSkipped()).isEqualTo(4);
                assertThat(completed.getCompletedAt()).isEqualTo(NOW.plusSeconds(4));
            });
        }
    }

    @SqlContractTest
    void countersThatAreNotReportedAreKept(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqImportJobRepository repository = new JooqImportJobRepository(database.dsl());
            database.unitOfWork().write(() -> repository.insert(job("job", ImportState.BUILDING, NOW)));

            write(database, () -> repository.recordExport("job", ImportState.BUILDING, new ExportCounts(5, 10, 1, 2), NOW));
            write(database, () -> repository.recordExport("job", ImportState.BUILDING,
                new ExportCounts(null, null, null, null), NOW.plusSeconds(1)));

            assertThat(repository.findById("job")).get().satisfies(stored -> {
                assertThat(stored.getRecordsProcessed()).isEqualTo(5);
                assertThat(stored.getTotalRecords()).isEqualTo(10);
                assertThat(stored.getExportSkipped()).isEqualTo(2);
            });
        }
    }

    @SqlContractTest
    void failingStaleJobsExcludesTheBoundaryAndOtherStatuses(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqImportJobRepository repository = new JooqImportJobRepository(database.dsl());
            database.unitOfWork().write(() -> {
                repository.insert(job("stale", ImportState.UPLOADING, NOW.minusMillis(1)));
                repository.insert(job("unknown-start", ImportState.UPLOADING, null));
                repository.insert(job("fresh", ImportState.UPLOADING, NOW));
                repository.insert(job("other", ImportState.PROCESSING, NOW.minusSeconds(60)));
            });

            long failed = database.unitOfWork().write(() -> repository.failStale(ImportState.UPLOADING, NOW,
                new ImportEnd(ImportEnding.TIMED_OUT, "timed out", NOW)));

            assertThat(failed).isEqualTo(2);
            assertThat(database.dsl().select(IMPORT_JOBS.ID).from(IMPORT_JOBS)
                .where(IMPORT_JOBS.END_REASON.eq(ImportEnding.TIMED_OUT.name())
                    .and(IMPORT_JOBS.ERROR_MESSAGE.eq("timed out"))
                    .and(IMPORT_JOBS.COMPLETED_AT.eq(NOW)))
                .fetch(IMPORT_JOBS.ID)).containsExactlyInAnyOrderElementsOf(List.of("stale", "unknown-start"));
        }
    }

    @SqlContractTest
    void aJobWithoutAProgressTimeIsTimedFromItsStart(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqImportJobRepository repository = new JooqImportJobRepository(database.dsl());
            database.unitOfWork().write(() -> {
                repository.insert(job("job", ImportState.PROCESSING, NOW));
                database.dsl().update(IMPORT_JOBS).setNull(IMPORT_JOBS.LAST_PROGRESS_AT).execute();
            });
            ImportEnd end = new ImportEnd(ImportEnding.TIMED_OUT, null, NOW);

            long atTheBoundary = database.unitOfWork().write(() -> repository.failStale(ImportState.PROCESSING, NOW, end));
            long afterIt = database.unitOfWork().write(
                () -> repository.failStale(ImportState.PROCESSING, NOW.plusMillis(1), end));

            assertThat(atTheBoundary).isZero();
            assertThat(afterIt).isEqualTo(1);
        }
    }

    private static boolean write(SqlTestDatabase database, Supplier<Boolean> update) {
        return database.unitOfWork().write(update);
    }

    private static MigrationStatus job(String id, ImportState state, @Nullable Instant startedAt) {
        return MigrationStatus.builder()
            .id(id)
            .type("litebans")
            .status(state)
            .startedAt(startedAt)
            .lastProgressAt(startedAt)
            .build();
    }
}
