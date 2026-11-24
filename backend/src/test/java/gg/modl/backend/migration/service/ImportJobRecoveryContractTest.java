package gg.modl.backend.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.repository.ImportJobRepository;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ImportJobRecoveryContractTest {
    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anInterruptedImportIsProcessedAgainFromItsAcceptedFileOnce(DialectTarget target) throws IOException {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.PROCESSING);
            kit.processing().reportProgress(jobId, new ImportedCounts(3000, 12, 5000));
            kit.storeUpload(kit.files().accepted(jobId).getFileName().toString());
            kit.clock().advance(Duration.ofHours(2));

            ImportJobRecovery recovery = kit.recovery();
            recovery.prepareResume();

            Path resumed = kit.files().resumed(jobId);
            MigrationStatus job = kit.job(jobId);
            assertThat(job.getStatus()).isEqualTo(ImportState.PROCESSING);
            assertThat(job.getRecordsProcessed()).isZero();
            assertThat(job.getTotalRecords()).isEqualTo(5000);
            assertThat(job.getLastProgressAt()).isEqualTo(kit.clock().instant());
            assertThat(kit.files().uploads()).containsExactly(resumed);
            verify(kit.worker(), never()).processFileAsync(any(), any(), any());

            recovery.resumeProcessing();
            recovery.resumeProcessing();

            verify(kit.worker(), times(1)).processFileAsync(any(), eq(jobId), eq(resumed));
        }
    }

    @SqlContractTest
    void aSecondInterruptionFailsTheImport(DialectTarget target) throws IOException {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.PROCESSING);
            kit.storeUpload(kit.files().accepted(jobId).getFileName().toString());
            kit.recovery().prepareResume();

            ImportJobRecovery secondBoot = kit.recovery();
            secondBoot.prepareResume();
            secondBoot.resumeProcessing();

            assertThat(kit.job(jobId).getEnding()).isEqualTo(ImportEnding.INTERRUPTED);
            assertThat(kit.files().uploads()).isEmpty();
            verify(kit.worker(), never()).processFileAsync(any(), any(), any());
        }
    }

    @SqlContractTest
    void anInterruptedImportWithoutItsFileFails(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.PROCESSING);

            kit.recovery().prepareResume();

            MigrationStatus job = kit.job(jobId);
            assertThat(job.getStatus()).isEqualTo(ImportState.FAILED);
            assertThat(job.getEnding()).isEqualTo(ImportEnding.INTERRUPTED);
            assertThat(job.getError()).isEqualTo(ImportJobRecovery.INTERRUPTED_MESSAGE);
        }
    }

    @SqlContractTest
    void anInterruptedReceiptWaitsForTheFileAgainAndLeavesNoPart(DialectTarget target) throws IOException {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.RECEIVING);
            kit.storeUpload("migration-" + jobId + "-123.part");
            kit.storeUpload(kit.files().accepted(jobId).getFileName().toString());

            kit.recovery().prepareResume();

            assertThat(kit.job(jobId).getStatus()).isEqualTo(ImportState.UPLOADING);
            assertThat(kit.files().uploads()).isEmpty();
        }
    }

    @SqlContractTest
    void downtimeDoesNotCountTowardsTheLease(DialectTarget target) {
        for (ImportState state : List.of(ImportState.BUILDING, ImportState.UPLOADING, ImportState.RECEIVING)) {
            try (ImportTestKit kit = ImportTestKit.start(target, dataDirectory.resolve(state.name()), new RetryInjection())) {
                String jobId = kit.startJobIn(state);
                kit.clock().advance(Duration.ofHours(2));

                kit.recovery().prepareResume();

                assertThat(kit.service().job(jobId).getStatus().isActive()).isTrue();
                assertThat(kit.job(jobId).getLastProgressAt()).isEqualTo(kit.clock().instant());
            }
        }
    }

    @SqlContractTest
    void bootDeletesLeftoversAndLeavesOtherFilesAndEndedJobsAlone(DialectTarget target) throws IOException {
        try (ImportTestKit kit = start(target)) {
            String jobId = kit.startJobIn(ImportState.COMPLETED);
            MigrationStatus ended = kit.job(jobId);
            kit.storeUpload("migration-" + jobId + ".json");
            Path unrelated = kit.storeUpload("notes.txt");

            kit.recovery().prepareResume();

            assertThat(kit.job(jobId)).isEqualTo(ended);
            assertThat(kit.files().uploads()).isEmpty();
            assertThat(Files.exists(unrelated)).isTrue();
        }
    }

    @SqlContractTest
    void bootingWithoutAnyJobWritesNothing(DialectTarget target) {
        try (ImportTestKit kit = start(target)) {
            kit.recovery().prepareResume();

            assertThat(kit.storedJobs()).isZero();
        }
    }

    @SqlContractTest
    void theJobIsRecoveredOnlyOnceThisStartHasCommitted(DialectTarget target) {
        try (ImportTestKit kit = start(target);
             AnnotationConfigApplicationContext application = new AnnotationConfigApplicationContext()) {
            String jobId = kit.startJobIn(ImportState.RECEIVING);
            application.registerBean(ImportJobRepository.class, kit::jobs);
            application.registerBean(ImportUploadFiles.class, kit::files);
            application.registerBean(ImportWorkerLauncher.class, kit::launcher);
            application.registerBean(UnitOfWork.class, () -> kit.instance().unitOfWork());
            application.registerBean(Clock.class, kit::clock);
            application.register(ImportJobRecovery.class);
            application.refresh();

            assertThat(kit.job(jobId).getStatus()).isEqualTo(ImportState.RECEIVING);

            application.publishEvent(new InstanceCommittedEvent());

            assertThat(kit.job(jobId).getStatus()).isEqualTo(ImportState.UPLOADING);
        }
    }

    private ImportTestKit start(DialectTarget target) {
        return ImportTestKit.start(target, dataDirectory, new RetryInjection());
    }
}
