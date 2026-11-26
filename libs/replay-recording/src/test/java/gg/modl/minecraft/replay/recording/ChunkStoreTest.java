package gg.modl.minecraft.replay.recording;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkStoreTest {
    private static final DeflaterPool DEFLATERS = new DeflaterPool(1);

    private static RawPacket packet(int size) {
        return RawPacket.compress(RawPacket.CHUNK_DATA, new byte[size], DEFLATERS);
    }

    @Test
    void everyStoredByteIsReleasedAgain() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        ChunkStore store = new ChunkStore(new RecordingMemory(budget));
        store.storeLight(0, 1, 1, packet(64), 0);
        store.store(0, 1, 1, packet(4096), -1, 1);
        store.store(0, 1, 1, packet(4096), -1, 2);
        store.storeLight(0, 1, 1, packet(64), 3);
        assertTrue(store.storePartial(0, 1, 1, packet(512), 4));
        store.fold(0, 5, 17, 70, 18, 1);
        RawPacket bulk = RawPacket.compress(RawPacket.LEGACY_BULK, new byte[2048], DEFLATERS);
        store.store(0, 2, 2, bulk, 0, 6);
        store.store(0, 3, 3, bulk, 1, 6);
        assertTrue(budget.usedBytes() > 0);
        assertEquals(budget.usedBytes(), store.retainedBytes());
        store.clear();
        assertEquals(0, budget.usedBytes());
        assertEquals(0, store.retainedBytes());
    }

    @Test
    void sheddingKeepsChunksInsideTheRadiusUntilNothingElseIsLeft() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        ChunkStore store = new ChunkStore(new RecordingMemory(budget));
        store.store(0, 0, 0, packet(256), -1, 0);
        store.store(0, 20, 0, packet(256), -1, 0);
        assertTrue(store.shedFarthest(0, 0, 0, 5, Long.MAX_VALUE) > 0);
        assertEquals(0, store.shedFarthest(0, 0, 0, 5, Long.MAX_VALUE));
        assertEquals(1, store.size());
        assertTrue(store.shedFarthest(0, 0, 0, -1, 1) > 0);
        assertEquals(0, store.size());
        assertTrue(store.truncated());
    }

    @Test
    void oneSheddingPassFreesTheFarthestChunksFirstUntilEnoughIsFree() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        ChunkStore store = new ChunkStore(new RecordingMemory(budget));
        for (int x = 0; x < 50; x++) store.store(0, x, 0, packet(256), -1, 0);
        store.store(1, 0, 0, packet(256), -1, 0);
        long oneChunk = store.retainedBytes() / 51;

        store.shedFarthest(0, 0, 0, -1, oneChunk * 3);

        assertEquals(48, store.size());
        assertEquals(budget.usedBytes(), store.retainedBytes());
        assertEquals(0, store.shedFarthest(0, 0, 0, 0, 0));
        store.shedFarthest(0, 0, 0, 0, oneChunk);
        assertEquals(47, store.size());
        store.shedFarthest(0, 0, 0, 46, Long.MAX_VALUE);
        assertEquals(47, store.size());
    }

    @Test
    void chunksOfOlderWorldsAndLongUnloadedChunksAreEvicted() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        ChunkStore store = new ChunkStore(new RecordingMemory(budget));
        store.store(0, 0, 0, packet(256), -1, 0);
        store.store(1, 0, 0, packet(256), -1, 0);
        store.store(1, 1, 0, packet(256), -1, 0);
        store.unload(1, 1, 0, 10);
        store.evict(100, 1, 200);
        assertEquals(1, store.size());
        store.clear();
        assertEquals(0, budget.usedBytes());
    }
}
