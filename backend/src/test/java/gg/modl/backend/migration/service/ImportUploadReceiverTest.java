package gg.modl.backend.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.exception.ExternalServiceException;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.support.MutableClock;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.InputStreamSource;

class ImportUploadReceiverTest {
    private static final String JOB = "0123456789abcdef";
    private static final byte[] EXPORT = "{\"players\":[{}]}".getBytes(StandardCharsets.UTF_8);
    private static final long MAX_BYTES = 1024;
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final FakeIntake intake = new FakeIntake();
    private final AtomicInteger openedBodies = new AtomicInteger();

    @Test
    void anUploadIsStoredAndAcceptedOnce() throws IOException {
        receiver().receive(JOB, EXPORT.length, body(EXPORT));

        assertThat(intake.state.get()).isEqualTo(State.PROCESSING);
        assertThat(Files.readAllBytes(files().accepted(JOB))).isEqualTo(EXPORT);
        assertThat(filesInDirectory()).containsExactly(files().accepted(JOB));
    }

    @Test
    void ofTwoSimultaneousUploadsOnlyOneIsReadAndAccepted() throws Exception {
        intake.pauseAfterStateCheck = new CyclicBarrier(2);
        ImportUploadReceiver receiver = receiver();
        List<Throwable> refusals = new CopyOnWriteArrayList<>();
        Runnable upload = () -> {
            try {
                receiver.receive(JOB, EXPORT.length, body(EXPORT));
            } catch (ImportUploadException refused) {
                refusals.add(refused);
            }
        };

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> first = threads.submit(upload);
            Future<?> second = threads.submit(upload);
            first.get(WAIT_SECONDS, TimeUnit.SECONDS);
            second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(openedBodies).hasValue(1);
        assertThat(intake.accepted).hasValue(1);
        assertThat(refusals).singleElement().isInstanceOfSatisfying(ImportUploadException.class,
            refused -> assertThat(refused.getMachineCode()).isEqualTo(ImportUploadException.NOT_ACCEPTED));
    }

    @Test
    void aSecondUploadAfterAcceptanceIsRefusedWithoutReadingIt() throws IOException {
        receiver().receive(JOB, EXPORT.length, body(EXPORT));
        byte[] other = "{\"players\":[]}".getBytes(StandardCharsets.UTF_8);

        assertRefused(() -> receiver().receive(JOB, other.length, body(other)), ImportUploadException.NOT_ACCEPTED);

        assertThat(openedBodies).hasValue(1);
        assertThat(intake.accepted).hasValue(1);
        assertThat(Files.readAllBytes(files().accepted(JOB))).isEqualTo(EXPORT);
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = "UPLOADING", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherStatusRefusesTheFileWithoutReadingOrChangingIt(State state) {
        intake.state.set(state);

        assertRefused(() -> receiver().receive(JOB, EXPORT.length, body(EXPORT)), ImportUploadException.NOT_ACCEPTED);

        assertThat(openedBodies).hasValue(0);
        assertThat(intake.state.get()).isEqualTo(state);
    }

    @Test
    void anOversizedStrayRequestCannotEndAnAcceptedImport() {
        intake.state.set(State.PROCESSING);

        assertRefused(() -> receiver().receive(JOB, MAX_BYTES + 1, body(EXPORT)), ImportUploadException.NOT_ACCEPTED);

        assertThat(intake.state.get()).isEqualTo(State.PROCESSING);
        assertThat(intake.ending.get()).isNull();
    }

    @Test
    void anOversizedFileEndsTheJobUnread() {
        assertRefused(() -> receiver().receive(JOB, MAX_BYTES + 1, body(EXPORT)), ImportUploadException.TOO_LARGE);

        assertThat(openedBodies).hasValue(0);
        assertThat(intake.ending.get()).isEqualTo(ImportEnding.FILE_TOO_LARGE);
    }

    @Test
    void aFullDiskEndsTheJobUnread() {
        ImportUploadReceiver receiver = receiver(new DiskSpaceFloor(Long.MAX_VALUE, 0));

        assertRefused(() -> receiver.receive(JOB, EXPORT.length, body(EXPORT)), ImportUploadException.DISK_FULL);

        assertThat(openedBodies).hasValue(0);
        assertThat(intake.ending.get()).isEqualTo(ImportEnding.UPLOAD_FAILED);
    }

    @Test
    void anUploadWithoutALengthIsRefusedUnreadAndStillAwaited() {
        assertRefused(() -> receiver().receive(JOB, -1, body(EXPORT)), ImportUploadException.LENGTH_REQUIRED);

        assertThat(openedBodies).hasValue(0);
        assertThat(intake.state.get()).isEqualTo(State.UPLOADING);
    }

    @Test
    void aShortBodyReopensTheJobForOneRetry() throws IOException {
        assertRefused(() -> receiver().receive(JOB, EXPORT.length + 1, body(EXPORT)), ImportUploadException.INCOMPLETE);
        assertThat(intake.state.get()).isEqualTo(State.UPLOADING);
        assertThat(filesInDirectory()).isEmpty();

        receiver().receive(JOB, EXPORT.length, body(EXPORT));

        assertThat(intake.accepted).hasValue(1);
        assertRefused(() -> receiver().receive(JOB, EXPORT.length, body(EXPORT)), ImportUploadException.NOT_ACCEPTED);
    }

    @Test
    void aLongerBodyThanDeclaredIsRefused() throws IOException {
        assertRefused(() -> receiver().receive(JOB, EXPORT.length - 1, body(EXPORT)), ImportUploadException.INCOMPLETE);

        assertThat(intake.state.get()).isEqualTo(State.UPLOADING);
        assertThat(filesInDirectory()).isEmpty();
    }

    @Test
    void aBrokenConnectionReopensTheJobAndLeavesNoFile() throws IOException {
        InputStreamSource broken = () -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Connection reset");
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new IOException("Connection reset");
            }
        };

        assertThatThrownBy(() -> receiver().receive(JOB, EXPORT.length, broken)).isInstanceOf(ExternalServiceException.class);

        assertThat(intake.state.get()).isEqualTo(State.UPLOADING);
        assertThat(filesInDirectory()).isEmpty();
    }

    @Test
    void aCancelDuringTheCopyStopsItAndAcceptsNothing() throws IOException {
        InputStreamSource cancelledWhileReading = () -> onEveryRead(() -> {
            intake.state.set(State.ENDED);
            clock.advance(ImportUploadReceiver.ACTIVE_CHECK_INTERVAL);
        });

        assertRefused(() -> receiver().receive(JOB, EXPORT.length, cancelledWhileReading), ImportUploadException.NOT_ACCEPTED);

        assertThat(intake.accepted).hasValue(0);
        assertThat(intake.state.get()).isEqualTo(State.ENDED);
        assertThat(filesInDirectory()).isEmpty();
    }

    @Test
    void aCancelJustBeforeAcceptanceDeletesTheFile() throws IOException {
        InputStreamSource cancelledWhileReading = () -> onEveryRead(() -> intake.state.set(State.ENDED));

        assertRefused(() -> receiver().receive(JOB, EXPORT.length, cancelledWhileReading), ImportUploadException.NOT_ACCEPTED);

        assertThat(intake.accepted).hasValue(0);
        assertThat(filesInDirectory()).isEmpty();
    }

    @Test
    void aBodyThatTakesTooLongReopensTheJob() throws IOException {
        Duration pastTheDeadline = ImportUploadReceiver.DEADLINE.plus(Duration.ofSeconds(1));
        InputStreamSource slow = () -> onEveryRead(() -> clock.advance(pastTheDeadline));

        assertRefused(() -> receiver().receive(JOB, EXPORT.length, slow), ImportUploadException.INCOMPLETE);

        assertThat(intake.state.get()).isEqualTo(State.UPLOADING);
        assertThat(filesInDirectory()).isEmpty();
    }

    private static InputStream onEveryRead(Runnable action) {
        return new FilterInputStream(new ByteArrayInputStream(EXPORT)) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                action.run();
                return read;
            }
        };
    }

    private InputStreamSource body(byte[] content) {
        return () -> {
            openedBodies.incrementAndGet();
            return new ByteArrayInputStream(content);
        };
    }

    private static void assertRefused(Runnable upload, String code) {
        assertThatThrownBy(upload::run).isInstanceOfSatisfying(ImportUploadException.class,
            refused -> assertThat(refused.getMachineCode()).isEqualTo(code));
    }

    private ImportUploadReceiver receiver() {
        return receiver(new DiskSpaceFloor(0, 0));
    }

    private ImportUploadReceiver receiver(DiskSpaceFloor floor) {
        return new ImportUploadReceiver(intake, files(), floor, MAX_BYTES, clock);
    }

    private ImportUploadFiles files() {
        return new ImportUploadFiles(directory);
    }

    private List<Path> filesInDirectory() throws IOException {
        try (Stream<Path> stored = Files.list(directory)) {
            return stored.map(Path::toAbsolutePath).toList();
        }
    }

    enum State {
        BUILDING,
        UPLOADING,
        RECEIVING,
        PROCESSING,
        ENDED
    }

    private static final class FakeIntake implements ImportFileIntake {
        private final AtomicReference<State> state = new AtomicReference<>(State.UPLOADING);
        private final AtomicReference<ImportEnding> ending = new AtomicReference<>();
        private final AtomicInteger accepted = new AtomicInteger();
        private volatile CyclicBarrier pauseAfterStateCheck;

        @Override
        public boolean awaitsFile(String jobId) {
            boolean awaited = state.get() == State.UPLOADING;
            CyclicBarrier pause = pauseAfterStateCheck;
            if (pause != null) {
                try {
                    pause.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("The second upload never checked the job", e);
                }
            }
            return awaited;
        }

        @Override
        public boolean claim(String jobId) {
            return state.compareAndSet(State.UPLOADING, State.RECEIVING);
        }

        @Override
        public boolean keepReceiving(String jobId) {
            return isReceiving(jobId);
        }

        @Override
        public boolean isReceiving(String jobId) {
            return state.get() == State.RECEIVING;
        }

        @Override
        public boolean accept(String jobId) {
            if (!state.compareAndSet(State.RECEIVING, State.PROCESSING)) {
                return false;
            }
            accepted.incrementAndGet();
            return true;
        }

        @Override
        public boolean reopen(String jobId) {
            return state.compareAndSet(State.RECEIVING, State.UPLOADING);
        }

        @Override
        public boolean refuse(String jobId, ImportEnding reason) {
            if (!state.compareAndSet(State.UPLOADING, State.ENDED)) {
                return false;
            }
            ending.set(reason);
            return true;
        }
    }
}
