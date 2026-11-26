package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingTest {
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final DeflaterPool DEFLATERS = new DeflaterPool(1);

    private static Recording recording(MemoryBudget budget, AtomicInteger radiusBlocks) {
        return recording(TARGET, budget, radiusBlocks);
    }

    private static Recording recording(UUID target, MemoryBudget budget, AtomicInteger radiusBlocks) {
        TestPacketEvents.install(ServerVersion.V_1_21_11);
        TranslationContext context = new TranslationContext(ServerVersion.V_1_21_11, new NameTable());
        Recording recording = new Recording(target, "Target", RecordingCause.AUTO_RECORD, context.getPlayerType(), budget,
                Runnable::run, () -> RadiusChunks.of(radiusBlocks.get()), e -> {
            throw e;
        });
        recording.enterWorld(WorldPackets.OVERWORLD, "minecraft:overworld", -64, 384, true, null, 42);
        recording.targetChunk(0, 0);
        return recording;
    }

    private static void fillJournal(Recording recording, int blocks) {
        long time = recording.now();
        int records = blocks * Journal.BLOCK_BYTES / 8;
        for (int i = 0; i < records; i++) recording.append(RecordEncoder.entityHead(time, i, 1F), time);
    }

    @Test
    void theRadiusInChunksCoversEveryBlockOfTheRadius() {
        assertEquals(0, RadiusChunks.of(0));
        assertEquals(1, RadiusChunks.of(1));
        assertEquals(1, RadiusChunks.of(16));
        assertEquals(4, RadiusChunks.of(64));
        assertEquals(5, RadiusChunks.of(65));
        assertEquals(8, RadiusChunks.of(128));
        assertEquals(0, RadiusChunks.of(-5));
    }

    @Test
    void aRunningRecordingShedsWithTheRadiusSetAfterItStarted() {
        AtomicInteger radiusBlocks = new AtomicInteger(160);
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        Recording recording = recording(budget, radiusBlocks);
        fillJournal(recording, 3);
        recording.storeChunk(0, 0, RawPacket.compress(RawPacket.CHUNK_DATA, new byte[512], DEFLATERS), -1, 0, recording.now());
        recording.storeChunk(6, 0, RawPacket.compress(RawPacket.CHUNK_DATA, new byte[512], DEFLATERS), -1, 0, recording.now());
        long before = recording.retainedBytes();

        radiusBlocks.set(32);
        recording.shedTo(before - 1);

        assertTrue(recording.retainedBytes() < before);
        assertEquals(1, recording.snapshot(recording.now()).getChunks().size());
        assertTrue(recording.snapshot(recording.now()).getBlocks().size() >= 3);
    }

    @Test
    void theModelIsCountedAgainstTheBudgetAndReleasedOnClose() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        Recording recording = recording(budget, new AtomicInteger(64));
        long time = recording.now();
        for (int i = 0; i < 2000; i++) {
            recording.append(RecordEncoder.playerInfo(time, new PlayerInfoPatch(new UUID(2L, i), "player" + i, "", false, 1, true, 0, "")), time);
        }
        fillJournal(recording, 1);

        recording.maintain(-60_000L);

        assertTrue(recording.retainedBytes() > 2000L * 192);
        assertEquals(budget.usedBytes(), recording.retainedBytes());
        recording.close();
        assertEquals(0, budget.usedBytes());
    }

    @Test
    void sheddingTakesEveryRecordingDownByTheSameShare() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        Recording recording = recording(budget, new AtomicInteger(64));
        Random random = new Random(7);
        for (int x = 0; x < 40; x++) {
            byte[] noise = new byte[4096];
            random.nextBytes(noise);
            recording.storeChunk(x, 0, RawPacket.compress(RawPacket.CHUNK_DATA, noise, DEFLATERS), -1, 0, recording.now());
        }
        long before = recording.retainedBytes();
        budget.limit(before / 2);

        recording.shedToShare(budget.keepFraction());

        assertTrue(recording.retainedBytes() <= before / 2);
        assertTrue(recording.snapshot(recording.now()).isTruncated());
    }

    @Test
    void twoRecordingsShedTheSameShareWhicheverRunsFirst() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        Recording first = recording(TARGET, budget, new AtomicInteger(16));
        Recording second = recording(UUID.randomUUID(), budget, new AtomicInteger(16));
        fillChunks(first, 60);
        fillChunks(second, 60);
        long firstBefore = first.retainedBytes();
        long secondBefore = second.retainedBytes();
        budget.limit(budget.usedBytes() / 2);
        double keep = budget.keepFraction();

        first.shedToShare(keep);
        second.shedToShare(keep);

        double firstKept = (double) first.retainedBytes() / firstBefore;
        double secondKept = (double) second.retainedBytes() / secondBefore;
        assertTrue(Math.abs(firstKept - secondKept) < 0.05, firstKept + " vs " + secondKept);
        assertTrue(firstKept <= keep && secondKept <= keep);
    }

    @Test
    void aCaptureOfARecordingOverHalfTheBudgetLeavesItWhole() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        Recording recording = recording(budget, new AtomicInteger(64));
        fillChunks(recording, 600);
        budget.limit(recording.retainedBytes() * 3 / 2 + 2L * 1024 * 1024);
        long before = recording.retainedBytes();

        LeasedSnapshot leased = recording.leaseSnapshot(recording.now());

        assertNotNull(leased);
        assertFalse(budget.exceeded());
        assertEquals(1.0, budget.keepFraction(), 1e-9);
        recording.shedToShare(budget.keepFraction());
        assertEquals(before, recording.retainedBytes());
        assertFalse(recording.snapshot(recording.now()).isTruncated());
        recording.close();
        assertTrue(budget.usedBytes() > 0);
        leased.release();
        assertEquals(0, budget.usedBytes());
    }

    private static void fillChunks(Recording recording, int count) {
        Random random = new Random(count);
        for (int x = 0; x < count; x++) {
            byte[] noise = new byte[4096];
            random.nextBytes(noise);
            recording.storeChunk(x % 40, x / 40, RawPacket.compress(RawPacket.CHUNK_DATA, noise, DEFLATERS), -1, 0, recording.now());
        }
    }
}
