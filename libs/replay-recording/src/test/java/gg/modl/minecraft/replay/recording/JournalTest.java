package gg.modl.minecraft.replay.recording;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTest {
    @Test
    void appendsAfterClearReserveNothing() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        Journal journal = new Journal(new RecordingMemory(budget));
        journal.append(RecordEncoder.worldTime(1, 6000), 1);
        assertTrue(budget.usedBytes() > 0);
        journal.clear();
        journal.append(RecordEncoder.worldTime(2, 6000), 2);
        assertEquals(0, budget.usedBytes());
        assertTrue(journal.snapshot(RecordingMemory.identitySet()).isEmpty());
    }

    @Test
    void closedBlocksOlderThanTheCutoffAreReleased() {
        MemoryBudget budget = new MemoryBudget(Long.MAX_VALUE);
        Journal journal = new Journal(new RecordingMemory(budget));
        for (int i = 0; i < 4000; i++) journal.append(RecordEncoder.chat(i, ChatPackets.SYSTEM, "{\"text\":\"line " + i + "\"}", null), i);
        long before = budget.usedBytes();
        assertTrue(!journal.takeClosedOlderThan(3000).isEmpty());
        assertTrue(budget.usedBytes() < before);
        journal.clear();
        assertEquals(0, budget.usedBytes());
    }
}
