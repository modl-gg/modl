package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypes;
import gg.modl.minecraft.replay.format.ReplayFileReader;
import gg.modl.minecraft.replay.format.ReplayLimits;
import gg.modl.proto.modl.v1.ReplayFrame;
import gg.modl.proto.modl.v1.ReplayStringTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayBoundsTest {
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final int MAX_WINDOW_SECONDS = 300;
    private static final long MAX_BUDGET_BYTES = 1024L * 1024L * 1024L;

    @Test
    void aCaptureOfTheLongestWindowAtTheLargestBudgetStaysWithinTheUploadLimits() throws IOException {
        long windowMs = MAX_WINDOW_SECONDS * 1000L;
        int entities = 420;
        Recording recording = recording(MAX_BUDGET_BYTES);
        TranslationContext context = context();
        int zombie = context.typeName(EntityTypes.ZOMBIE);
        for (int id = 0; id < entities; id++) {
            recording.append(RecordEncoder.entitySpawn(0, 1000 + id, zombie, null, Fixed.of(id % 20), Fixed.of(65), Fixed.of(id / 20.0),
                    0F, 0F, 0F, 0), 0);
        }
        for (long time = 50; time <= windowMs; time += 50) {
            for (int id = 0; id < entities; id++) {
                recording.append(RecordEncoder.entityMove(time, 1000 + id, 1, 0, -1, false, 0F, 0F, true), time);
            }
        }
        MemoryTarget target = new MemoryTarget();

        composer().composeWithinLimits(new ComposeRequest("bounded", "test", recording.snapshot(windowMs), windowMs, 128), target);

        Counts counts = Counts.of(target.bytes());
        assertTrue(target.bytes().length <= ReplayLimits.MAX_COMPRESSED_BYTES);
        assertTrue(counts.events <= ReplayLimits.MAX_EVENTS, counts.events + " events");
        assertTrue(counts.events > ReplayLimits.MAX_EVENTS / 4, counts.events + " events");
        assertTrue(counts.keyframes <= ReplayLimits.MAX_KEYFRAMES);
        assertTrue(counts.ended);
    }

    @Test
    void anOversizedCaptureKeepsTheNewestPartOfTheWindow() throws IOException {
        Recording recording = recording(64L * 1024 * 1024);
        recording.append(RecordEncoder.entitySpawn(0, 7, context().typeName(EntityTypes.ZOMBIE), null, 0, Fixed.of(65), 0,
                0F, 0F, 0F, 0), 0);
        for (long time = 100; time <= 60_000; time += 100) {
            recording.append(RecordEncoder.entityMove(time, 7, 1, 0, 0, false, 0F, 0F, true), time);
        }
        ReplayBounds bounds = ReplayBounds.UPLOAD.toBuilder().events(200).build();
        MemoryTarget target = new MemoryTarget();

        composer().composeWithin(new ComposeRequest("trimmed", "test", recording.snapshot(60_000), 60_000, 64), target, bounds);

        Counts counts = Counts.of(target.bytes());
        assertTrue(counts.events <= 200, counts.events + " events");
        assertTrue(counts.windowMs < 60_000 && counts.windowMs >= ReplayComposer.MIN_WINDOW_MS);
        assertTrue(target.discarded > 0);
    }

    @Test
    void aCaptureThatCannotFitEvenTheShortestWindowIsRefused() {
        Recording recording = recording(64L * 1024 * 1024);
        recording.append(RecordEncoder.entitySpawn(0, 7, context().typeName(EntityTypes.ZOMBIE), null, 0, Fixed.of(65), 0,
                0F, 0F, 0F, 0), 0);
        for (long time = 10; time <= 60_000; time += 10) {
            recording.append(RecordEncoder.entityMove(time, 7, 1, 0, 0, false, 0F, 0F, true), time);
        }
        ReplayBounds bounds = ReplayBounds.UPLOAD.toBuilder().events(10).build();

        assertThrows(ReplayTooLargeException.class, () -> composer().composeWithin(new ComposeRequest("refused", "test",
                recording.snapshot(60_000), 60_000, 64), new MemoryTarget(), bounds));
    }

    @Test
    void terrainOverflowNarrowsTheRadiusAndHistoryOverflowShortensTheWindow() {
        ComposeRequest request = new ComposeRequest("shrink", "test", recording(64L * 1024 * 1024).snapshot(0), 60_000, 128);

        ComposeRequest terrain = ReplayComposer.smaller(request, ReplayTooLargeException.Overflow.TERRAIN);
        ComposeRequest history = ReplayComposer.smaller(request, ReplayTooLargeException.Overflow.HISTORY);
        ComposeRequest size = ReplayComposer.smaller(request, ReplayTooLargeException.Overflow.SIZE);

        assertTrue(terrain != null && terrain.getRadiusBlocks() == 64 && terrain.getWindowMs() == 60_000);
        assertTrue(history != null && history.getRadiusBlocks() == 128 && history.getWindowMs() == 30_000);
        assertTrue(size != null && size.getRadiusBlocks() == 64 && size.getWindowMs() == 30_000);
        ComposeRequest smallest = request.withWindowMs(ReplayComposer.MIN_WINDOW_MS).withRadiusBlocks(ReplayComposer.MIN_RADIUS_BLOCKS);
        assertTrue(ReplayComposer.smaller(smallest, ReplayTooLargeException.Overflow.SIZE) == null);
    }

    @Test
    void aFrameOverTheFrameLimitIsTooLargeRatherThanAWriteFailure() throws IOException {
        StringBuilder huge = new StringBuilder();
        while (huge.length() <= ReplayLimits.MAX_FRAME_BYTES) huge.append("xxxxxxxxxxxxxxxx");
        ReplayFrame frame = ReplayFrame.newBuilder().setStrings(ReplayStringTable.newBuilder().addValues(huge.toString())).build();
        try (BoundedReplayWriter writer = new BoundedReplayWriter(new ByteArrayOutputStream(), ReplayBounds.UPLOAD)) {
            ReplayTooLargeException tooLarge = assertThrows(ReplayTooLargeException.class, () -> writer.write(frame));
            assertTrue(tooLarge.overflow() == ReplayTooLargeException.Overflow.SIZE);
        }
    }

    private static Recording recording(long budgetBytes) {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        Recording recording = new Recording(TARGET, "Target", RecordingCause.AUTO_RECORD, context().getPlayerType(),
                new MemoryBudget(budgetBytes), Runnable::run, () -> RadiusChunks.of(128), e -> {
            throw e;
        });
        recording.enterWorld(WorldPackets.OVERWORLD, "minecraft:overworld", -64, 384, true, DimensionTypes.OVERWORLD, 42);
        return recording;
    }

    private static TranslationContext context() {
        return new TranslationContext(ServerVersion.V_1_21_11, new NameTable());
    }

    private static ReplayComposer composer() {
        TranslationContext context = context();
        return new ReplayComposer(RecorderSettings.builder().windowSeconds(MAX_WINDOW_SECONDS).radiusBlocks(128)
                .memoryBudgetBytes(MAX_BUDGET_BYTES).minecraftVersion("1.21.11").dataVersion(0).serverSoftware("test")
                .recorderVersion("test").build(), ServerVersion.V_1_21_11, new NameTable(),
                context.typeName(EntityTypes.FALLING_BLOCK), Logger.getAnonymousLogger());
    }

    private static final class MemoryTarget implements ComposeTarget {
        private ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int discarded;

        @Override
        public OutputStream open() {
            buffer = new ByteArrayOutputStream();
            return buffer;
        }

        @Override
        public void discard() {
            discarded++;
        }

        byte[] bytes() {
            return buffer.toByteArray();
        }
    }

    private static final class Counts {
        private long events;
        private int keyframes;
        private long windowMs;
        private boolean ended;

        static Counts of(byte[] file) throws IOException {
            Counts counts = new Counts();
            try (ReplayFileReader reader = ReplayFileReader.open(new ByteArrayInputStream(file), ReplayLimits.MAX_UNCOMPRESSED_BYTES)) {
                ReplayFrame frame;
                while ((frame = reader.next()) != null) {
                    switch (frame.getFrameCase()) {
                        case HEADER -> counts.windowMs = frame.getHeader().getDurationMs();
                        case EVENTS -> counts.events += frame.getEvents().getEventsCount();
                        case KEYFRAME -> counts.keyframes++;
                        case END -> counts.ended = true;
                        default -> {
                        }
                    }
                }
            }
            return counts;
        }
    }
}
