package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import gg.modl.minecraft.replay.format.ReplayFileFormat;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayRecorderTest {
    private static final long BUDGET_BYTES = 8L * 1024 * 1024;

    @TempDir
    Path output;

    private final UUID target = UUID.randomUUID();
    private ReplayRecorder recorder;

    @BeforeEach
    void create() {
        recorder = recorder(BUDGET_BYTES);
    }

    @AfterEach
    void close() {
        recorder.close();
    }

    private ReplayRecorder recorder(long budgetBytes) {
        return recorder(budgetBytes, new TestOutput(output));
    }

    private static ReplayRecorder recorder(long budgetBytes, ReplayOutput replayOutput) {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        ReplayRecorder created = new ReplayRecorder(RecorderSettings.builder().windowSeconds(60).radiusBlocks(64).memoryBudgetBytes(budgetBytes)
                .minecraftVersion("1.21.11").dataVersion(0).serverSoftware("test").recorderVersion("test").build(),
                replayOutput, Logger.getAnonymousLogger());
        created.recordEveryPlayer(true);
        return created;
    }

    @Test
    void joiningPlayersAreRecordedFromTheirJoinPacketOnlyWhileEveryPlayerIsRecorded() {
        User joining = user(target, openChannel());
        recorder.recordEveryPlayer(false);

        assertNull(recorder.joining(joining));
        assertFalse(recorder.isRecording(target));

        recorder.recordEveryPlayer(true);
        assertNotNull(recorder.joining(joining));
        assertTrue(recorder.isRecording(target));
    }

    @Test
    void aJoinPacketReachesTheRecordingTheJoinEventAlreadyStarted() {
        assertTrue(recorder.start(target, "Steve"));

        assertNotNull(recorder.joining(user(target, openChannel())));
    }

    @Test
    void aClosedConnectionIsNeverRecordedAtJoin() {
        EmbeddedChannel channel = openChannel();
        channel.close().syncUninterruptibly();

        assertNull(recorder.joining(user(target, channel)));
        assertFalse(recorder.isRecording(target));
    }

    @Test
    void turningRecordEveryPlayerOffEndsEveryAutomaticRecordingButNoOther() {
        UUID staffed = UUID.randomUUID();
        assertTrue(recorder.start(target, "Steve"));
        assertNotNull(recorder.startSeeded(staffed, "Staffed", RecordingCause.STAFF));

        recorder.recordEveryPlayer(false);

        assertFalse(recorder.isRecording(target));
        assertTrue(recorder.isRecording(staffed));
        assertFalse(recorder.start(target, "Steve"));
        assertNull(recorder.startSeeded(target, "Steve", RecordingCause.AUTO_RECORD));
    }

    private static EmbeddedChannel openChannel() {
        return new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    }

    private static User user(UUID uuid, EmbeddedChannel channel) {
        return new User(channel, ConnectionState.PLAY, ClientVersion.V_1_21_11, new UserProfile(uuid, "Joiner"));
    }

    @Test
    void aStaffRecordingStopsOnlyForItsStarter() {
        assertNotNull(recorder.startSeeded(target, "Steve", RecordingCause.STAFF));

        assertFalse(recorder.stopIfStartedBy(target, RecordingCause.AUTO_RECORD));
        assertTrue(recorder.stopIfStartedBy(target, RecordingCause.STAFF));
        assertFalse(recorder.isRecording(target));
        assertFalse(recorder.status(target).isPresent());
    }

    @Test
    void aFlagKeepsAStaffRecordingAsAFlagRecording() {
        assertNotNull(recorder.startSeeded(target, "Steve", RecordingCause.STAFF));

        assertTrue(recorder.keepForFlag(target));

        assertEquals(RecordingCause.ANTICHEAT_FLAG, recorder.status(target).map(RecordingStatus::getCause).orElse(null));
        assertFalse(recorder.stopIfStartedBy(target, RecordingCause.STAFF));
        assertTrue(recorder.isRecording(target));
    }

    @Test
    void aFlagKeepsAnAutoRecordingWhenAutoRecordIsTurnedOff() {
        assertTrue(recorder.start(target, "Steve"));

        assertTrue(recorder.keepForFlag(target));

        assertEquals(RecordingCause.ANTICHEAT_FLAG, recorder.status(target).map(RecordingStatus::getCause).orElse(null));
        assertFalse(recorder.stopIfStartedBy(target, RecordingCause.AUTO_RECORD));
        assertTrue(recorder.isRecording(target));
    }

    @Test
    void aFlagFindsNothingToKeepOnceTheStaffStopStarted() {
        assertNotNull(recorder.startSeeded(target, "Steve", RecordingCause.STAFF));
        assertTrue(recorder.stopIfStartedBy(target, RecordingCause.STAFF));

        assertFalse(recorder.keepForFlag(target));
        assertNotNull(recorder.startSeeded(target, "Steve", RecordingCause.ANTICHEAT_FLAG));
    }

    @Test
    void aSecondStartLeavesTheFirstCause() {
        assertTrue(recorder.start(target, "Steve"));

        assertNull(recorder.startSeeded(target, "Steve", RecordingCause.STAFF));
        assertEquals(RecordingCause.AUTO_RECORD, recorder.status(target).map(RecordingStatus::getCause).orElse(null));
    }

    @Test
    void theBudgetReportsItsLimitAndTheRecordingsSharingIt() {
        recorder.start(target, "Steve");

        assertEquals(BUDGET_BYTES, recorder.limitBytes());
        assertEquals(1, recorder.activeRecordings());
        assertTrue(recorder.status(target).map(RecordingStatus::getRecordingMillis).orElse(-1L) >= 0);
    }

    @Test
    void newRecordingsAreRefusedOnceTheBudgetCannotGiveEachItsShare() {
        recorder.close();
        recorder = recorder(2 * MemoryBudget.MIN_RECORDING_BYTES);

        assertTrue(recorder.start(UUID.randomUUID(), "One"));
        assertTrue(recorder.start(UUID.randomUUID(), "Two"));

        assertFalse(recorder.start(UUID.randomUUID(), "Three"));
        assertNull(recorder.startSeeded(UUID.randomUUID(), "Four", RecordingCause.AUTO_RECORD));
        assertEquals(2, recorder.activeRecordings());
    }

    @Test
    void aCaptureOfAPlayerWhoIsNotRecordedIsRefusedWithAReason() throws Exception {
        ReplayCapture capture = recorder.capture(target, "test").get(5, TimeUnit.SECONDS);

        assertNull(capture.getReplay());
        assertEquals(ReplayCapture.Refusal.NOT_RECORDING, capture.getRefusal());
    }

    @Test
    void aCaptureIsWrittenAndItsReservationIsReleased() throws Exception {
        seed(recorder.startSeeded(target, "Steve", RecordingCause.STAFF));
        long before = recorder.usedBytes();

        ReplayCapture capture = recorder.capture(target, "test").get(10, TimeUnit.SECONDS);

        CapturedReplay replay = capture.getReplay();
        assertNotNull(replay);
        assertNull(capture.getRefusal());
        assertTrue(Files.isRegularFile(replay.getFile().toPath()));
        assertTrue(replay.getFile().getName().endsWith(ReplayFileFormat.FILE_EXTENSION));
        assertEquals(before, recorder.usedBytes());
        assertEquals(0, partialFiles());
    }

    @Test
    void aCaptureThatTheBudgetCannotHoldIsRefusedWithAReason() throws Exception {
        RecordingSeed seed = recorder.startSeeded(target, "Steve", RecordingCause.STAFF);
        seed(seed);
        recorder.retune(60, 64, 1024);

        ReplayCapture capture = recorder.capture(target, "test").get(5, TimeUnit.SECONDS);

        assertNull(capture.getReplay());
        assertEquals(ReplayCapture.Refusal.MEMORY_BUDGET, capture.getRefusal());
        assertEquals(0, filesIn());
    }

    @Test
    void aCaptureBeyondTheTwoWaitingForTheWriterIsRefusedAsBusy() throws Exception {
        recorder.close();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        recorder = recorder(BUDGET_BYTES, new HeldOutput(new TestOutput(output), writing, release));
        seed(recorder.startSeeded(target, "Steve", RecordingCause.STAFF));

        CompletableFuture<ReplayCapture> running = recorder.capture(target, "running");
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        List<CompletableFuture<ReplayCapture>> waiting = new ArrayList<>();
        for (int i = 0; i < ReplayRecorder.MAX_PENDING_CAPTURES; i++) waiting.add(recorder.capture(target, "waiting"));
        ReplayCapture busy = recorder.capture(target, "busy").get(5, TimeUnit.SECONDS);
        release.countDown();

        assertEquals(ReplayCapture.Refusal.WRITER_BUSY, busy.getRefusal());
        assertNotNull(running.get(10, TimeUnit.SECONDS).getReplay());
        for (CompletableFuture<ReplayCapture> capture : waiting) assertNotNull(capture.get(10, TimeUnit.SECONDS).getReplay());
    }

    @Test
    void aStaffRequestOnAnAutoRecordedPlayerKeepsThatRecordingFromBeingDisplaced() {
        recorder.close();
        recorder = recorder(2 * MemoryBudget.MIN_RECORDING_BYTES);
        UUID requested = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertTrue(recorder.start(requested, "Requested"));
        assertTrue(recorder.start(other, "Other"));

        assertNull(recorder.startSeeded(requested, "Requested", RecordingCause.STAFF));
        assertNotNull(recorder.startSeeded(UUID.randomUUID(), "Flagged", RecordingCause.ANTICHEAT_FLAG));

        assertTrue(recorder.isRecording(requested));
        assertFalse(recorder.isRecording(other));
        assertEquals(RecordingCause.AUTO_RECORD, recorder.status(requested).map(RecordingStatus::getCause).orElse(null));
    }

    @Test
    void aFlaggedStaffRecordingKeepsItsStaffRank() {
        recorder.close();
        recorder = recorder(2 * MemoryBudget.MIN_RECORDING_BYTES);
        UUID staffed = UUID.randomUUID();
        UUID flagged = UUID.randomUUID();
        assertNotNull(recorder.startSeeded(staffed, "Staffed", RecordingCause.STAFF));
        assertNotNull(recorder.startSeeded(flagged, "Flagged", RecordingCause.ANTICHEAT_FLAG));
        assertTrue(recorder.keepForFlag(staffed));

        assertNotNull(recorder.startSeeded(UUID.randomUUID(), "Second staff", RecordingCause.STAFF));

        assertTrue(recorder.isRecording(staffed));
        assertFalse(recorder.isRecording(flagged));
    }

    @Test
    void staffAndFlagRecordingsTakeTheSlotOfTheOldestAutoRecording() {
        recorder.close();
        recorder = recorder(2 * MemoryBudget.MIN_RECORDING_BYTES);
        UUID oldest = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID flagged = UUID.randomUUID();
        assertTrue(recorder.start(oldest, "Oldest"));
        assertTrue(recorder.start(newer, "Newer"));

        assertFalse(recorder.start(UUID.randomUUID(), "Late"));
        assertNotNull(recorder.startSeeded(staff, "Staff", RecordingCause.STAFF));
        assertFalse(recorder.isRecording(oldest));
        assertTrue(recorder.isRecording(newer));
        assertNotNull(recorder.startSeeded(flagged, "Flagged", RecordingCause.ANTICHEAT_FLAG));
        assertFalse(recorder.isRecording(newer));
        assertNull(recorder.startSeeded(UUID.randomUUID(), "Second flag", RecordingCause.ANTICHEAT_FLAG));
        assertNotNull(recorder.startSeeded(UUID.randomUUID(), "Second staff", RecordingCause.STAFF));
        assertFalse(recorder.isRecording(flagged));
        assertTrue(recorder.isRecording(staff));
    }

    @Test
    void detachingCancelsQueuedWritesAndDeletesTheRunningOne() throws Exception {
        recorder.close();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        recorder = recorder(BUDGET_BYTES, new HeldOutput(new TestOutput(output), writing, release));
        seed(recorder.startSeeded(target, "Steve", RecordingCause.STAFF));

        CompletableFuture<ReplayCapture> running = recorder.capture(target, "first");
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        CompletableFuture<ReplayCapture> queued = recorder.capture(target, "second");
        recorder.detach();
        release.countDown();
        recorder.awaitShutdown();

        assertEquals(ReplayCapture.Refusal.STOPPED, running.get(5, TimeUnit.SECONDS).getRefusal());
        assertEquals(ReplayCapture.Refusal.STOPPED, queued.get(5, TimeUnit.SECONDS).getRefusal());
        assertEquals(0, filesIn());
    }

    private static void seed(RecordingSeed seed) {
        assertNotNull(seed);
        seed.world("minecraft:overworld", "world", null, 42).position(0.5, 65, 0.5, 0, 0, true);
    }

    private long filesIn() throws IOException {
        if (!Files.isDirectory(output)) return 0;
        try (Stream<Path> files = Files.list(output)) {
            return files.count();
        }
    }

    private long partialFiles() throws IOException {
        if (!Files.isDirectory(output)) return 0;
        try (Stream<Path> files = Files.list(output)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".partial")).count();
        }
    }

    private static final class HeldOutput implements ReplayOutput {
        private final ReplayOutput delegate;
        private final CountDownLatch writing;
        private final CountDownLatch release;

        private HeldOutput(ReplayOutput delegate, CountDownLatch writing, CountDownLatch release) {
            this.delegate = delegate;
            this.writing = writing;
            this.release = release;
        }

        @Override
        public Path directory() {
            return delegate.directory();
        }

        @Override
        public OutputStream create(Path file) throws IOException {
            OutputStream created = delegate.create(file);
            writing.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return created;
        }
    }
}
