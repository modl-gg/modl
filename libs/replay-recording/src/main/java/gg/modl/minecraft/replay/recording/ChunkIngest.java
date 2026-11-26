package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;

final class ChunkIngest {
    private static final long MAX_PENDING_BYTES = 32L * 1024 * 1024;
    private static final int MAX_PACKET_BYTES = 8 * 1024 * 1024;

    private final AtomicLong pendingBytes = new AtomicLong();
    private final DecodeFailures failures;
    private final MemoryBudget budget;
    private final DeflaterPool deflaters;
    private final boolean fullChunkFlag;

    ChunkIngest(DecodeFailures failures, MemoryBudget budget, DeflaterPool deflaters, boolean fullChunkFlag) {
        this.failures = failures;
        this.budget = budget;
        this.deflaters = deflaters;
        this.fullChunkFlag = fullChunkFlag;
    }

    void chunk(PacketSendEvent event, Recording recording, long time, int kind) {
        byte[] raw = copy(event, recording);
        if (raw == null) return;
        int segment = recording.currentSegment();
        submit(recording, raw.length, () -> {
            RawPacket packet = RawPacket.compress(kind, raw, deflaters);
            if (kind == RawPacket.LEGACY_BULK) {
                LegacyChunkFormat.Bulk bulk = LegacyChunkFormat.bulkHeader(raw);
                for (int i = 0; i < bulk.size(); i++) {
                    recording.storeChunk(bulk.chunkX(i), bulk.chunkZ(i), packet, i, segment, time);
                }
            } else if (kind == RawPacket.LEGACY_CHUNK && LegacyChunkFormat.isUnload(raw)) {
                recording.unloadChunk(readInt(raw, 0), readInt(raw, 4), segment, time);
            } else if (raw.length > 8 && isPartial(kind, raw)) {
                recording.storePartial(readInt(raw, 0), readInt(raw, 4), packet, segment, time);
            } else if (raw.length >= 8) {
                recording.storeChunk(readInt(raw, 0), readInt(raw, 4), packet, -1, segment, time);
            }
        });
    }

    void light(PacketSendEvent event, Recording recording, long time) {
        byte[] raw = copy(event, recording);
        if (raw == null) return;
        int segment = recording.currentSegment();
        submit(recording, raw.length, () -> {
            VarIntCursor cursor = new VarIntCursor(raw);
            int chunkX = cursor.next();
            int chunkZ = cursor.next();
            recording.storeLight(chunkX, chunkZ, RawPacket.compress(RawPacket.LIGHT_UPDATE, raw, deflaters), segment, time);
        });
    }

    void unload(Recording recording, int chunkX, int chunkZ, long time) {
        int segment = recording.currentSegment();
        submit(recording, 0, () -> recording.unloadChunk(chunkX, chunkZ, segment, time));
    }

    long pendingBytes() {
        return pendingBytes.get();
    }

    private byte @Nullable [] copy(PacketSendEvent event, Recording recording) {
        byte[] raw = SentPacketBytes.of(event, MAX_PACKET_BYTES);
        if (raw == null || pendingBytes.get() + raw.length > maxPendingBytes()) {
            recording.markTruncated();
            return null;
        }
        return raw;
    }

    private long maxPendingBytes() {
        return Math.min(MAX_PENDING_BYTES, budget.limitBytes() / 4);
    }

    private void submit(Recording recording, int bytes, Task task) {
        pendingBytes.addAndGet(bytes);
        budget.reserve(bytes);
        try {
            recording.serial().execute(() -> {
                try {
                    if (!recording.closed()) task.run();
                } catch (DataFormatException | RuntimeException e) {
                    failures.record("chunk", e);
                } finally {
                    settle(bytes);
                }
            });
        } catch (RejectedExecutionException e) {
            settle(bytes);
        }
    }

    private void settle(int bytes) {
        pendingBytes.addAndGet(-bytes);
        budget.release(bytes);
    }

    private boolean isPartial(int kind, byte[] raw) {
        boolean flagged = kind == RawPacket.LEGACY_CHUNK || (kind == RawPacket.CHUNK_DATA && fullChunkFlag);
        return flagged && raw[8] == 0;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16) | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private interface Task {
        void run() throws DataFormatException;
    }
}
