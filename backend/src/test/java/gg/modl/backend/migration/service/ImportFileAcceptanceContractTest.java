package gg.modl.backend.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.migration.data.ExportCounts;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.ImportState;
import gg.modl.backend.migration.data.ImportedCounts;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.InputStreamSource;

class ImportFileAcceptanceContractTest {
    private static final byte[] FIRST = "{\"players\":[{\"upload\":1}]}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECOND = "{\"players\":[{\"upload\":2}]}".getBytes(StandardCharsets.UTF_8);
    private static final long WAIT_SECONDS = 30;

    @TempDir
    Path directory;

    private final AtomicInteger openedBodies = new AtomicInteger();

    @SqlContractTest
    void ofTwoSimultaneousUploadsExactlyOneIsReadAcceptedAndProcessed(DialectTarget target) throws Exception {
        try (ImportTestKit kit = ImportTestKit.start(target, directory, new RetryInjection())) {
            String jobId = kit.startJobIn(ImportState.UPLOADING);
            CyclicBarrier bothSawUploading = new CyclicBarrier(2);
            ImportUploadReceiver receiver = kit.receiver(new PausingIntake(kit.intake(), bothSawUploading));
            List<ImportUploadException> refusals = new CopyOnWriteArrayList<>();

            try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
                Future<?> first = threads.submit(() -> upload(receiver, jobId, FIRST, refusals));
                Future<?> second = threads.submit(() -> upload(receiver, jobId, SECOND, refusals));
                first.get(WAIT_SECONDS, TimeUnit.SECONDS);
                second.get(WAIT_SECONDS, TimeUnit.SECONDS);
            }

            assertThat(openedBodies).hasValue(1);
            assertThat(refusals).singleElement()
                .satisfies(refused -> assertThat(refused.getMachineCode()).isEqualTo(ImportUploadException.NOT_ACCEPTED));
            assertThat(kit.job(jobId).getStatus()).isEqualTo(ImportState.PROCESSING);
            assertThat(Files.readAllBytes(kit.files().accepted(jobId))).isIn(FIRST, SECOND);
            verify(kit.worker(), times(1)).processFileAsync(any(), eq(jobId), eq(kit.files().accepted(jobId)));
        }
    }

    @SqlContractTest
    void everyStatusButUploadingRefusesAFileUnread(DialectTarget target) {
        for (ImportState state : ImportState.values()) {
            if (state == ImportState.UPLOADING) {
                continue;
            }
            try (ImportTestKit kit = ImportTestKit.start(target, directory.resolve(state.name()), new RetryInjection())) {
                String jobId = kit.startJobIn(state);

                assertRefused(kit, jobId, FIRST);

                assertThat(openedBodies).hasValue(0);
                assertThat(kit.job(jobId).getStatus()).isEqualTo(state);
                verify(kit.worker(), never()).processFileAsync(any(), anyString(), any());
            }
        }
    }

    @SqlContractTest
    void aSecondUploadIsRefusedAfterAcceptanceDuringProcessingAndAfterCompletion(DialectTarget target) throws IOException {
        try (ImportTestKit kit = ImportTestKit.start(target, directory, new RetryInjection())) {
            String jobId = kit.startJobIn(ImportState.UPLOADING);
            kit.receiver(kit.intake()).receive(jobId, FIRST.length, body(FIRST));

            assertRefused(kit, jobId, SECOND);
            kit.service().reportExport(jobId, true, new ExportCounts(9, 9, 0, 0));
            kit.service().reportExport(jobId, false, new ExportCounts(9, 9, 0, 0));
            assertRefused(kit, jobId, SECOND);
            kit.processing().complete(kit.readyServer(), jobId, new ImportedCounts(1, 0, 1));
            assertRefused(kit, jobId, SECOND);

            assertThat(openedBodies).hasValue(1);
            assertThat(Files.readAllBytes(kit.files().accepted(jobId))).isEqualTo(FIRST);
            verify(kit.worker(), times(1)).processFileAsync(any(), eq(jobId), any());
        }
    }

    @SqlContractTest
    void aLateUploadForAnEndedJobLeavesTheNextJobAlone(DialectTarget target) {
        try (ImportTestKit kit = ImportTestKit.start(target, directory, new RetryInjection())) {
            String endedJob = kit.startJobIn(ImportState.FAILED);
            kit.clock().advance(Duration.ofSeconds(1));
            String nextJob = kit.startJobIn(ImportState.UPLOADING);

            assertRefused(kit, endedJob, FIRST);

            assertThat(openedBodies).hasValue(0);
            assertThat(kit.job(nextJob).getStatus()).isEqualTo(ImportState.UPLOADING);
        }
    }

    @SqlContractTest
    void aLateExportReportCannotMoveAClaimedOrAcceptedJobBack(DialectTarget target) {
        for (ImportState state : List.of(ImportState.RECEIVING, ImportState.PROCESSING)) {
            try (ImportTestKit kit = ImportTestKit.start(target, directory.resolve(state.name()), new RetryInjection())) {
                String jobId = kit.startJobIn(state);

                kit.service().reportExport(jobId, false, new ExportCounts(1, 1, 0, 0));
                kit.service().reportExport(jobId, true, new ExportCounts(1, 1, 0, 0));

                assertThat(kit.job(jobId).getStatus()).isEqualTo(state);
            }
        }
    }

    @SqlContractTest
    void aFailedBodyAllowsARetryAndThenAgainOnlyOneIsAccepted(DialectTarget target) {
        try (ImportTestKit kit = ImportTestKit.start(target, directory, new RetryInjection())) {
            String jobId = kit.startJobIn(ImportState.UPLOADING);
            ImportUploadReceiver receiver = kit.receiver(kit.intake());

            assertThatThrownBy(() -> receiver.receive(jobId, FIRST.length + 1, body(FIRST)))
                .isInstanceOf(ImportUploadException.class);
            assertThat(kit.job(jobId).getStatus()).isEqualTo(ImportState.UPLOADING);

            receiver.receive(jobId, FIRST.length, body(FIRST));
            assertRefused(kit, jobId, SECOND);

            verify(kit.worker(), times(1)).processFileAsync(any(), eq(jobId), any());
        }
    }

    @SqlContractTest
    void aCancelDuringTheCopyAcceptsNothing(DialectTarget target) throws IOException {
        try (ImportTestKit kit = ImportTestKit.start(target, directory, new RetryInjection())) {
            String jobId = kit.startJobIn(ImportState.UPLOADING);
            InputStreamSource cancelledWhileRead = () -> new FilterInputStream(new ByteArrayInputStream(FIRST)) {
                private boolean cancelled;

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (!cancelled) {
                        cancelled = true;
                        kit.service().end(kit.readyServer(), jobId, ImportEnding.CANCELLED, null, null);
                    }
                    return super.read(buffer, offset, length);
                }
            };

            assertThatThrownBy(() -> kit.receiver(kit.intake()).receive(jobId, FIRST.length, cancelledWhileRead))
                .isInstanceOf(ImportUploadException.class);

            assertThat(kit.job(jobId).getEnding()).isEqualTo(ImportEnding.CANCELLED);
            assertThat(kit.files().uploads()).isEmpty();
            verify(kit.worker(), never()).processFileAsync(any(), anyString(), any());
        }
    }

    @SqlContractTest
    void recoveryNeverLetsAnAcceptedJobTakeAnotherFile(DialectTarget target) {
        try (ImportTestKit kit = ImportTestKit.start(target, directory, new RetryInjection())) {
            String jobId = kit.startJobIn(ImportState.UPLOADING);
            kit.receiver(kit.intake()).receive(jobId, FIRST.length, body(FIRST));

            kit.recovery().prepareResume();
            assertRefused(kit, jobId, SECOND);
            kit.recovery().prepareResume();
            assertRefused(kit, jobId, SECOND);

            assertThat(openedBodies).hasValue(1);
        }
    }

    private void upload(ImportUploadReceiver receiver, String jobId, byte[] content, List<ImportUploadException> refusals) {
        try {
            receiver.receive(jobId, content.length, body(content));
        } catch (ImportUploadException refused) {
            refusals.add(refused);
        }
    }

    private void assertRefused(ImportTestKit kit, String jobId, byte[] content) {
        assertThatThrownBy(() -> kit.receiver(kit.intake()).receive(jobId, content.length, body(content)))
            .isInstanceOfSatisfying(ImportUploadException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo(ImportUploadException.NOT_ACCEPTED));
    }

    private InputStreamSource body(byte[] content) {
        return () -> {
            openedBodies.incrementAndGet();
            return new ByteArrayInputStream(content);
        };
    }

    private record PausingIntake(ImportFileIntake intake, CyclicBarrier afterCheck) implements ImportFileIntake {
        @Override
        public boolean awaitsFile(String jobId) {
            boolean awaited = intake.awaitsFile(jobId);
            try {
                afterCheck.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("The second upload never checked the job", e);
            }
            return awaited;
        }

        @Override
        public boolean claim(String jobId) {
            return intake.claim(jobId);
        }

        @Override
        public boolean keepReceiving(String jobId) {
            return intake.keepReceiving(jobId);
        }

        @Override
        public boolean isReceiving(String jobId) {
            return intake.isReceiving(jobId);
        }

        @Override
        public boolean accept(String jobId) {
            return intake.accept(jobId);
        }

        @Override
        public boolean reopen(String jobId) {
            return intake.reopen(jobId);
        }

        @Override
        public boolean refuse(String jobId, ImportEnding ending) {
            return intake.refuse(jobId, ending);
        }
    }
}
