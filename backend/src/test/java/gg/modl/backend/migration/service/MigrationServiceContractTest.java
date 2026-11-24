package gg.modl.backend.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.migration.data.ExportCounts;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.migration.data.IpHistoryRetention;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.data.ScopedPunishments;
import gg.modl.backend.player.repository.ImportedAddressRetention;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;

class MigrationServiceContractTest {
    private static final int CONCURRENT_STARTS = 16;
    private static final long WAIT_SECONDS = 60;
    private static final ExportCounts EXPORTED = new ExportCounts(3, 4, 1, 2);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aStartStoresOneJobWithItsOriginAndChoicesAndAuditsIt(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJob();

            MigrationStatus job = kit.job(jobId);
            assertThat(job.getType()).isEqualTo("litebans");
            assertThat(job.getStatus()).isEqualTo(ImportState.BUILDING);
            assertThat(job.getStartedFrom()).isEqualTo("Proxy");
            assertThat(job.getStartedAddress()).isEqualTo("198.51.100.7");
            assertThat(job.getScopedPunishments()).isEqualTo(ScopedPunishments.SKIP);
            assertThat(job.getIpHistory()).isEqualTo(IpHistoryRetention.STANDARD);
            assertThat(job.getStartedAt()).isEqualTo(SqlTestDatabase.START);
            assertThat(kit.storedJobs()).isEqualTo(1);
            assertThat(kit.auditEntries()).singleElement().asString()
                .contains("LiteBans import started from the console of Proxy", "skip", "standard");
        }
    }

    @SqlContractTest
    void ofSixteenConcurrentStartsOneWins(DialectTarget target) throws Exception {
        try (ImportTestKit kit = start(target)) {
            List<Boolean> started = new ArrayList<>();
            try (ExecutorService threads = Executors.newFixedThreadPool(CONCURRENT_STARTS)) {
                List<Future<Boolean>> attempts = new ArrayList<>();
                for (int attempt = 0; attempt < CONCURRENT_STARTS; attempt++) {
                    attempts.add(threads.submit(() -> {
                        try {
                            kit.startJob();
                            return true;
                        } catch (ConflictException refused) {
                            assertThat(refused.getMachineCode()).isEqualTo(MigrationService.IMPORT_ACTIVE);
                            return false;
                        }
                    }));
                }
                for (Future<Boolean> attempt : attempts) {
                    started.add(attempt.get(WAIT_SECONDS, TimeUnit.SECONDS));
                }
            }

            assertThat(started).containsOnlyOnce(true);
            assertThat(kit.storedJobs()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void anotherSupportedSourceStartsAJobOfItsTypeAndNamesItInTheAudit(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            MigrationStatus job = kit.service().start(kit.readyServer(), "banmanager", ImportTestKit.ORIGIN,
                ScopedPunishments.WIDEN, IpHistoryRetention.KEEP);

            assertThat(kit.job(job.getId()).getType()).isEqualTo("banmanager");
            assertThat(kit.auditEntries()).singleElement().asString().startsWith("BanManager import started");
        }
    }

    @SqlContractTest
    void anUnfinishedSetupAndAnUnknownTypeStoreNothing(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            assertThatThrownBy(() -> kit.service().start(TestServers.server("server", null), "litebans", ImportTestKit.ORIGIN,
                ScopedPunishments.WIDEN, IpHistoryRetention.KEEP))
                .isInstanceOfSatisfying(ConflictException.class,
                    refused -> assertThat(refused.getMachineCode()).isEqualTo(MigrationService.SETUP_REQUIRED));
            assertThatThrownBy(() -> kit.service().start(kit.readyServer(), "maxbans", ImportTestKit.ORIGIN,
                ScopedPunishments.WIDEN, IpHistoryRetention.KEEP))
                .isInstanceOf(ValidationException.class);

            assertThat(kit.storedJobs()).isZero();
            assertThat(kit.retained().latestRetainedSighting()).isEmpty();
        }
    }

    @SqlContractTest
    void theExportReportMovesOnlyAJobThatIsBuilding(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJob();

            kit.service().reportExport(jobId, false, new ExportCounts(1, 4, 0, 0));
            assertThat(kit.job(jobId).getStatus()).isEqualTo(ImportState.BUILDING);

            MigrationStatus uploading = kit.service().reportExport(jobId, true, EXPORTED);
            assertThat(uploading.getStatus()).isEqualTo(ImportState.UPLOADING);
            assertThat(uploading.getExportFailed()).isEqualTo(1);
            assertThat(uploading.getExportSkipped()).isEqualTo(2);

            MigrationStatus late = kit.service().reportExport(jobId, false, new ExportCounts(0, 0, 0, 0));
            assertThat(late.getStatus()).isEqualTo(ImportState.UPLOADING);
            assertThat(late.getRecordsProcessed()).isEqualTo(3);
        }
    }

    @SqlContractTest
    void anUnknownIdIsNotFound(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            for (String id : List.of("missing", "x".repeat(40))) {
                assertThatThrownBy(() -> kit.service().job(id)).isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> kit.service().reportExport(id, false, EXPORTED))
                    .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> kit.service().end(kit.readyServer(), id, ImportEnding.CANCELLED, null, null))
                    .isInstanceOf(ResourceNotFoundException.class);
            }
        }
    }

    @SqlContractTest
    void aJobBeforeAcceptanceTimesOutFiveMinutesAfterItsLastReport(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.UPLOADING);
            kit.clock().advance(Duration.ofMinutes(4));
            kit.service().reportExport(jobId, true, EXPORTED);

            kit.clock().advance(Duration.ofMinutes(5));
            assertThat(kit.service().job(jobId).getStatus()).isEqualTo(ImportState.UPLOADING);

            kit.clock().advance(Duration.ofMillis(1));
            MigrationStatus timedOut = kit.service().job(jobId);
            assertThat(timedOut.getStatus()).isEqualTo(ImportState.FAILED);
            assertThat(timedOut.getEnding()).isEqualTo(ImportEnding.TIMED_OUT);
            assertThat(timedOut.getError()).isEqualTo(MigrationService.TIMED_OUT_MESSAGE);
        }
    }

    @SqlContractTest
    void theCopyOfAFileKeepsItsLease(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.RECEIVING);

            kit.clock().advance(Duration.ofMinutes(4));
            assertThat(kit.intake().keepReceiving(jobId)).isTrue();
            kit.clock().advance(Duration.ofMinutes(4));
            assertThat(kit.service().job(jobId).getStatus()).isEqualTo(ImportState.RECEIVING);

            kit.clock().advance(Duration.ofMinutes(2));
            assertThat(kit.service().job(jobId).getEnding()).isEqualTo(ImportEnding.TIMED_OUT);
        }
    }

    @SqlContractTest
    void processingTimesOutThirtyMinutesAfterTheWorkersLastReport(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.PROCESSING);
            kit.clock().advance(Duration.ofMinutes(29));
            kit.processing().reportProgress(jobId, new ImportedCounts(500, 0, 5000));
            kit.service().reportExport(jobId, true, EXPORTED);

            kit.clock().advance(Duration.ofMinutes(30));
            assertThat(kit.service().job(jobId).getStatus()).isEqualTo(ImportState.PROCESSING);

            kit.clock().advance(Duration.ofMillis(1));
            assertThat(kit.service().job(jobId).getEnding()).isEqualTo(ImportEnding.TIMED_OUT);
            assertThat(kit.processing().shouldContinue(jobId)).isFalse();
        }
    }

    @SqlContractTest
    void aCancelEndsEveryActiveStatusAndAnEndedJobIsReturnedUnchanged(DialectTarget target) {
        for (ImportState state : ImportState.ACTIVE) {
            try (ImportTestKit kit = ImportTestKit.start(target, dataDirectory.resolve(state.name()), new RetryInjection())) {
                String jobId = kit.startJobIn(state);

                MigrationStatus cancelled = kit.service().end(kit.readyServer(), jobId, ImportEnding.CANCELLED, null, "192.0.2.4");
                MigrationStatus again = kit.service().end(kit.readyServer(), jobId, ImportEnding.EXPORT_FAILED, "late", null);

                assertThat(cancelled.getStatus()).isEqualTo(ImportState.FAILED);
                assertThat(cancelled.getEnding()).isEqualTo(ImportEnding.CANCELLED);
                assertThat(again).isEqualTo(cancelled);
                assertThat(kit.auditEntries()).hasSize(2).last().asString().contains("cancelled", "192.0.2.4");
            }
        }
    }

    @SqlContractTest
    void onlyACancelFromThePluginEndsAnAcceptedImport(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.PROCESSING);

            for (ImportEnding ending : List.of(ImportEnding.INTERRUPTED, ImportEnding.EXPORT_FAILED,
                ImportEnding.UPLOAD_FAILED, ImportEnding.FILE_TOO_LARGE)) {
                assertThat(kit.service().end(kit.readyServer(), jobId, ending, null, null).getStatus())
                    .isEqualTo(ImportState.PROCESSING);
            }
            assertThatThrownBy(() -> kit.service().end(kit.readyServer(), jobId, ImportEnding.IMPORT_FAILED, null, null))
                .isInstanceOf(ValidationException.class);
        }
    }

    @SqlContractTest
    void anotherImportMayStartStraightAfterEveryKindOfEnding(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            for (ImportState state : List.of(ImportState.BUILDING, ImportState.PROCESSING)) {
                String cancelled = kit.startJobIn(state);
                kit.service().end(kit.readyServer(), cancelled, ImportEnding.CANCELLED, null, null);
                kit.clock().advance(Duration.ofMillis(1));
            }
            kit.startJobIn(ImportState.COMPLETED);
            kit.clock().advance(Duration.ofMillis(1));
            kit.startJobIn(ImportState.FAILED);
            kit.clock().advance(Duration.ofMillis(1));
            kit.startJob();
            kit.clock().advance(Duration.ofMinutes(6));
            String afterTimeout = kit.startJob();

            assertThat(kit.storedJobs()).isEqualTo(6);
            assertThat(kit.service().overview(kit.readyServer()).job()).get()
                .extracting(MigrationStatus::getId).isEqualTo(afterTimeout);
        }
    }

    @SqlContractTest
    void aWorkerOfAnEndedJobCannotSeeOrWriteTheNextJob(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String endedJob = kit.startJobIn(ImportState.PROCESSING);
            kit.clock().advance(Duration.ofMinutes(31));
            String nextJob = kit.startJob();

            assertThat(kit.processing().shouldContinue(endedJob)).isFalse();
            kit.processing().reportProgress(endedJob, new ImportedCounts(900, 0, 900));
            kit.processing().complete(kit.readyServer(), endedJob, new ImportedCounts(900, 0, 900));

            assertThat(kit.job(endedJob).getEnding()).isEqualTo(ImportEnding.TIMED_OUT);
            assertThat(kit.job(nextJob).getStatus()).isEqualTo(ImportState.BUILDING);
        }
    }

    @SqlContractTest
    void aShutdownStopsTheWorkerWithoutEndingTheJob(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.PROCESSING);

            kit.processing().stopForShutdown();

            assertThat(kit.processing().shouldContinue(jobId)).isFalse();
            assertThat(kit.processing().hasEnded(jobId)).isFalse();
        }
    }

    @SqlContractTest
    void aWorkerFailureStoresItsReasonAndCounts(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.PROCESSING);

            kit.processing().fail(jobId, ImportEnding.INVALID_FILE, "bad\u0000 file", new ImportedCounts(7, 1, 9));

            MigrationStatus failed = kit.job(jobId);
            assertThat(failed.getEnding()).isEqualTo(ImportEnding.INVALID_FILE);
            assertThat(failed.getError()).isEqualTo("bad file");
            assertThat(failed.getRecordsProcessed()).isEqualTo(7);
        }
    }

    @SqlContractTest
    void theChoiceOfAJobDecidesWhetherItsWrittenAddressesAreRetained(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String keep = kit.startJob(IpHistoryRetention.KEEP);

            assertThat(kit.processing().addressRetention(keep)).isEqualTo(ImportedAddressRetention.KEPT);
            assertThat(kit.service().overview(kit.readyServer()).ipHistoryKeptBefore()).isEmpty();

            kit.service().end(kit.readyServer(), keep, ImportEnding.CANCELLED, null, null);
            kit.clock().advance(Duration.ofSeconds(1));
            String standard = kit.startJob(IpHistoryRetention.STANDARD);

            assertThat(kit.processing().addressRetention(standard)).isEqualTo(ImportedAddressRetention.STANDARD);
        }
    }

    @SqlContractTest
    void standardEndsAnEarlierKeepOnlyWhenTheImportCompletes(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            Instant seen = SqlTestDatabase.START.minus(Duration.ofDays(700));
            kit.retainAddressSeenAt(seen);
            assertThat(kit.service().overview(kit.readyServer()).ipHistoryKeptBefore()).contains(seen);
            String failed = kit.startJobIn(ImportState.PROCESSING);
            kit.processing().fail(failed, ImportEnding.IMPORT_FAILED, "broken", ImportedCounts.NONE);
            assertThat(kit.retained().latestRetainedSighting()).contains(seen);

            kit.clock().advance(Duration.ofSeconds(1));
            String completed = kit.startJobIn(ImportState.PROCESSING);
            assertThat(kit.retained().latestRetainedSighting()).contains(seen);
            kit.processing().complete(kit.readyServer(), completed, new ImportedCounts(1, 0, 1));

            assertThat(kit.retained().latestRetainedSighting()).isEmpty();
            assertThat(kit.auditEntries()).last().asString().contains("no longer kept");
        }
    }

    @SqlContractTest
    void anInterruptedReleaseIsFinishedByTheRetentionSweep(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            kit.retainAddressSeenAt(SqlTestDatabase.START.minus(Duration.ofDays(700)));
            kit.startJobIn(ImportState.COMPLETED);

            assertThat(kit.retained().anyRetained()).isTrue();
            assertThat(kit.service().overview(kit.readyServer()).ipHistoryKeptBefore()).isEmpty();
            assertThat(kit.release().purge(kit.readyServer(), kit.clock().instant())).isEqualTo(1);
            assertThat(kit.retained().anyRetained()).isFalse();
            assertThat(kit.release().purge(kit.readyServer(), kit.clock().instant())).isZero();
        }
    }

    @SqlContractTest
    void aStartIsRefusedWhileAnInterruptedReleaseIsPendingAndDrainsNothingOnTheRequest(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            kit.retainAddressSeenAt(SqlTestDatabase.START.minus(Duration.ofDays(700)));
            kit.startJobIn(ImportState.COMPLETED);
            kit.clock().advance(Duration.ofSeconds(1));

            assertThatThrownBy(() -> kit.startJob(IpHistoryRetention.KEEP))
                .isInstanceOfSatisfying(ConflictException.class,
                    refused -> assertThat(refused.getMachineCode()).isEqualTo(MigrationService.IP_HISTORY_RELEASE_RUNNING));
            assertThat(kit.retained().anyRetained()).isTrue();

            kit.release().purge(kit.readyServer(), kit.clock().instant());

            assertThat(kit.startJob(IpHistoryRetention.KEEP)).isNotBlank();
        }
    }

    @SqlContractTest
    void aStartIsRefusedWhileTheDatabaseIsMoving(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            kit.databaseMoving(true);

            assertThatThrownBy(() -> kit.startJob(IpHistoryRetention.STANDARD))
                .isInstanceOfSatisfying(ConflictException.class,
                    refused -> assertThat(refused.getMachineCode()).isEqualTo(MigrationService.DATABASE_MOVING));
            assertThat(kit.storedJobs()).isZero();

            kit.databaseMoving(false);

            assertThat(kit.startJob(IpHistoryRetention.STANDARD)).isNotBlank();
        }
    }

    @SqlContractTest
    void aStartIsRefusedWhileUploadedFilesAreMovingToAnotherStorage(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            kit.storageMoving(true);

            assertThatThrownBy(() -> kit.startJob(IpHistoryRetention.STANDARD))
                .isInstanceOfSatisfying(ConflictException.class,
                    refused -> assertThat(refused.getMachineCode()).isEqualTo(MigrationService.STORAGE_MOVING));
            assertThat(kit.storedJobs()).isZero();

            kit.storageMoving(false);

            assertThat(kit.startJob(IpHistoryRetention.STANDARD)).isNotBlank();
        }
    }

    @SqlContractTest
    void aCompletedStandardImportWithNothingLeftHeldDoesNotBlockTheNextStart(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            kit.startJobIn(ImportState.COMPLETED);
            kit.clock().advance(Duration.ofSeconds(1));

            assertThat(kit.startJob(IpHistoryRetention.STANDARD)).isNotBlank();
        }
    }

    @SqlContractTest
    void theBackgroundReleaseStopsWhenAskedToAndTheSweepFinishesIt(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            kit.retainAddressSeenAt(SqlTestDatabase.START.minus(Duration.ofDays(700)));
            kit.startJobIn(ImportState.COMPLETED);

            assertThat(kit.release().releaseWhile(() -> false)).isZero();
            assertThat(kit.release().isPending()).isTrue();
            assertThat(kit.release().releaseWhile(() -> true)).isEqualTo(1);
            assertThat(kit.release().isPending()).isFalse();
        }
    }

    @SqlContractTest
    void holdsOfACompletedKeepOrARunningImportAreNeverReleased(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            kit.startJobIn(ImportState.PROCESSING);
            kit.retainAddressSeenAt(SqlTestDatabase.START.minus(Duration.ofDays(700)));

            assertThat(kit.release().purge(kit.readyServer(), kit.clock().instant())).isZero();

            kit.completeKeeping();

            assertThat(kit.release().purge(kit.readyServer(), kit.clock().instant())).isZero();
            assertThat(kit.retained().anyRetained()).isTrue();
        }
    }

    private ImportTestKit start(DialectTarget target) {
        return ImportTestKit.start(target, dataDirectory, new RetryInjection());
    }
}
