package gg.modl.minecraft.replay.recording;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryBudgetTest {
    @Test
    void snapshotReservationsCountAgainstTheLimitUntilReleased() {
        MemoryBudget budget = new MemoryBudget(1000);
        budget.reserve(600);

        assertTrue(budget.reserveSnapshot(500));
        assertTrue(budget.exceeded());
        assertEquals(1100, budget.usedBytes());
        assertFalse(budget.reserveSnapshot(501));

        budget.releaseSnapshot(500);
        assertFalse(budget.exceeded());
        assertEquals(600, budget.usedBytes());
    }

    @Test
    void aSnapshotLargerThanTheWholeBudgetIsRefused() {
        MemoryBudget budget = new MemoryBudget(1000);

        assertFalse(budget.reserveSnapshot(1001));
        assertEquals(0, budget.reservedBytes());
    }

    @Test
    void recordingsAreAdmittedOnlyWhileEachKeepsItsMinimumShare() {
        MemoryBudget budget = new MemoryBudget(3 * MemoryBudget.MIN_RECORDING_BYTES);

        assertTrue(budget.admits(3));
        assertFalse(budget.admits(4));
        assertTrue(budget.reserveSnapshot(MemoryBudget.MIN_RECORDING_BYTES));
        assertFalse(budget.admits(3));
        assertTrue(budget.admits(2));
    }

    @Test
    void everyRecordingKeepsTheSameFractionOfItsBytes() {
        MemoryBudget budget = new MemoryBudget(1000);
        budget.reserve(2000);

        assertEquals(0.45, budget.keepFraction(), 1e-9);

        budget.release(1500);
        assertEquals(1.0, budget.keepFraction(), 1e-9);
    }

    @Test
    void bytesALiveRecordingFreesWhileASnapshotHoldsThemMoveToTheReservation() {
        MemoryBudget budget = new MemoryBudget(1000);
        RecordingMemory memory = new RecordingMemory(budget);
        Object shared = new Object();
        memory.charge(400);
        Set<Object> pinned = RecordingMemory.identitySet();
        pinned.add(shared);
        RecordingMemory.Lease lease = memory.lease(pinned, 50);

        memory.freeShared(shared, 400);
        assertEquals(0, budget.liveBytes());
        assertEquals(450, budget.reservedBytes());

        memory.release(lease);
        assertEquals(0, budget.usedBytes());
    }

    @Test
    void aSecondSnapshotOfTheSameDataKeepsItCountedUntilBothEnd() {
        MemoryBudget budget = new MemoryBudget(1000);
        RecordingMemory memory = new RecordingMemory(budget);
        Object shared = new Object();
        memory.charge(300);
        Set<Object> first = RecordingMemory.identitySet();
        first.add(shared);
        Set<Object> second = RecordingMemory.identitySet();
        second.add(shared);
        RecordingMemory.Lease one = memory.lease(first, 0);
        RecordingMemory.Lease two = memory.lease(second, 0);

        memory.freeShared(shared, 300);
        memory.release(one);
        assertEquals(300, budget.usedBytes());
        memory.release(two);
        assertEquals(0, budget.usedBytes());
    }
}
