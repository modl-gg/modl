package gg.modl.minecraft.replay.recording;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

final class ChunkStore {
    private static final long ORPHAN_LIGHT_MS = 5_000L;
    private static final long LIGHT_CARRY_MS = 2_000L;
    private static final int MAX_ORPHAN_LIGHT = 2048;

    private final Map<Long, StoredChunk> chunks = new HashMap<>();
    private final Map<Long, TimedPacket> orphanLight = new HashMap<>();
    private final RecordingMemory memory;
    private final AtomicLong retainedBytes = new AtomicLong();
    private boolean truncated;

    ChunkStore(RecordingMemory memory) {
        this.memory = memory;
    }

    void store(int segment, int chunkX, int chunkZ, RawPacket packet, int bulkIndex, long time) {
        long key = BlockPositions.chunkKey(segment, chunkX, chunkZ);
        StoredChunk previous = chunks.remove(key);
        if (packet.retain()) charge(packet.retainedBytes());
        StoredChunk chunk = new StoredChunk(packet, bulkIndex, time);
        chunks.put(key, chunk);
        charge(chunk.retainedBytes());
        if (previous != null) {
            for (TimedPacket light : previous.lightSince(time - LIGHT_CARRY_MS)) attachLight(chunk, new TimedPacket(light.getPacket(), light.getTime()));
            forget(previous);
        }
        TimedPacket orphan = orphanLight.remove(key);
        if (orphan != null) {
            free(orphan.getPacket().retainedBytes());
            attachLight(chunk, orphan);
        }
    }

    boolean storePartial(int segment, int chunkX, int chunkZ, RawPacket packet, long time) {
        StoredChunk chunk = chunks.get(BlockPositions.chunkKey(segment, chunkX, chunkZ));
        if (chunk == null) return false;
        long before = chunk.retainedBytes();
        if (!chunk.addPartial(new TimedPacket(packet, time))) {
            truncated = true;
            return false;
        }
        adjust(chunk.retainedBytes() - before);
        return true;
    }

    void storeLight(int segment, int chunkX, int chunkZ, RawPacket update, long time) {
        long key = BlockPositions.chunkKey(segment, chunkX, chunkZ);
        StoredChunk chunk = chunks.get(key);
        TimedPacket light = new TimedPacket(update, time);
        if (chunk != null) {
            attachLight(chunk, light);
            return;
        }
        if (orphanLight.size() >= MAX_ORPHAN_LIGHT) return;
        TimedPacket replaced = orphanLight.put(key, light);
        charge(update.retainedBytes());
        if (replaced != null) free(replaced.getPacket().retainedBytes());
    }

    private void attachLight(StoredChunk chunk, TimedPacket light) {
        charge(light.getPacket().retainedBytes());
        TimedPacket dropped = chunk.addLightUpdate(light);
        if (dropped != null) freeShared(dropped.getPacket());
    }

    void unload(int segment, int chunkX, int chunkZ, long time) {
        StoredChunk chunk = chunks.get(BlockPositions.chunkKey(segment, chunkX, chunkZ));
        if (chunk != null) chunk.unload(time);
    }

    void fold(int segment, long time, int x, int y, int z, int state) {
        if (segment < 0) return;
        long key = BlockPositions.chunkKey(segment, x >> 4, z >> 4);
        StoredChunk chunk = chunks.get(key);
        if (chunk == null) return;
        long before = chunk.retainedBytes();
        if (!chunk.overlay(time, x, y, z, state)) {
            chunks.remove(key);
            forget(chunk);
            truncated = true;
            return;
        }
        adjust(chunk.retainedBytes() - before);
    }

    void evict(long unloadedBefore, int oldestSegment, long now) {
        Iterator<Map.Entry<Long, StoredChunk>> iterator = chunks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, StoredChunk> entry = iterator.next();
            StoredChunk chunk = entry.getValue();
            boolean unloadedLongAgo = chunk.unloadedAt() >= 0 && chunk.unloadedAt() < unloadedBefore;
            if (unloadedLongAgo || BlockPositions.chunkSegment(entry.getKey()) < oldestSegment) {
                iterator.remove();
                forget(chunk);
            }
        }
        Iterator<TimedPacket> orphans = orphanLight.values().iterator();
        while (orphans.hasNext()) {
            TimedPacket light = orphans.next();
            if (light.getTime() < now - ORPHAN_LIGHT_MS) {
                orphans.remove();
                free(light.getPacket().retainedBytes());
            }
        }
    }

    long shedFarthest(int segment, int chunkX, int chunkZ, int keepRadiusChunks, long bytesToFree) {
        if (bytesToFree <= 0 || chunks.isEmpty()) return 0;
        long keep = keepRadiusChunks < 0 ? -1 : (long) keepRadiusChunks * keepRadiusChunks;
        List<Long> candidates = new ArrayList<>();
        for (Long key : chunks.keySet()) {
            if (distance(key, segment, chunkX, chunkZ) > keep) candidates.add(key);
        }
        candidates.sort((left, right) -> Long.compare(distance(right, segment, chunkX, chunkZ), distance(left, segment, chunkX, chunkZ)));
        long before = retainedBytes.get();
        for (Long key : candidates) {
            if (before - retainedBytes.get() >= bytesToFree) break;
            StoredChunk chunk = chunks.remove(key);
            if (chunk != null) forget(chunk);
            truncated = true;
        }
        return before - retainedBytes.get();
    }

    private static long distance(long key, int segment, int chunkX, int chunkZ) {
        if (BlockPositions.chunkSegment(key) != segment) return Long.MAX_VALUE;
        long dx = BlockPositions.chunkX(key) - (long) chunkX;
        long dz = BlockPositions.chunkZ(key) - (long) chunkZ;
        return dx * dx + dz * dz;
    }

    Map<Long, StoredChunk> snapshot(Set<Object> pinned) {
        Map<Long, StoredChunk> copy = new HashMap<>(chunks.size() * 2);
        for (Map.Entry<Long, StoredChunk> entry : chunks.entrySet()) {
            StoredChunk chunk = entry.getValue().snapshot();
            chunk.pinPackets(pinned);
            copy.put(entry.getKey(), chunk);
        }
        return copy;
    }

    boolean truncated() {
        return truncated;
    }

    long retainedBytes() {
        return retainedBytes.get();
    }

    int size() {
        return chunks.size();
    }

    void clear() {
        for (TimedPacket light : orphanLight.values()) free(light.getPacket().retainedBytes());
        orphanLight.clear();
        List<StoredChunk> all = new ArrayList<>(chunks.values());
        chunks.clear();
        for (StoredChunk chunk : all) forget(chunk);
    }

    private void forget(StoredChunk chunk) {
        free(chunk.ownBytes());
        for (TimedPacket partial : chunk.partials()) freeShared(partial.getPacket());
        for (TimedPacket light : chunk.lightUpdates()) freeShared(light.getPacket());
        if (chunk.packet().release()) freeShared(chunk.packet());
    }

    private void charge(long bytes) {
        retainedBytes.addAndGet(bytes);
        memory.charge(bytes);
    }

    private void free(long bytes) {
        retainedBytes.addAndGet(-bytes);
        memory.free(bytes);
    }

    private void adjust(long delta) {
        if (delta >= 0) charge(delta);
        else free(-delta);
    }

    private void freeShared(RawPacket packet) {
        long bytes = packet.retainedBytes();
        retainedBytes.addAndGet(-bytes);
        memory.freeShared(packet, bytes);
    }
}
