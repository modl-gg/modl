package gg.modl.minecraft.replay.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecordingMemory {
    private final MemoryBudget budget;
    private final List<Lease> leases = new ArrayList<>();

    RecordingMemory(MemoryBudget budget) {
        this.budget = budget;
    }

    MemoryBudget budget() {
        return budget;
    }

    void charge(long bytes) {
        budget.reserve(bytes);
    }

    void free(long bytes) {
        budget.release(bytes);
    }

    void adjust(long delta) {
        if (delta >= 0) charge(delta);
        else free(-delta);
    }

    synchronized boolean hasLeases() {
        return !leases.isEmpty();
    }

    synchronized void freeShared(Object resource, long bytes) {
        for (Lease lease : leases) {
            if (lease.adopt(resource, bytes)) {
                budget.adopt(bytes);
                return;
            }
        }
        budget.release(bytes);
    }

    @Nullable Lease lease(Set<Object> pinned, long exclusiveBytes) {
        if (!budget.reserveSnapshot(exclusiveBytes)) return null;
        Lease lease = new Lease(pinned, exclusiveBytes);
        synchronized (this) {
            leases.add(lease);
        }
        return lease;
    }

    synchronized void release(Lease lease) {
        if (!leases.remove(lease)) return;
        budget.releaseSnapshot(lease.exclusiveBytes);
        for (Map.Entry<Object, Long> adopted : lease.adopted.entrySet()) {
            if (!handOver(adopted.getKey(), adopted.getValue())) budget.releaseSnapshot(adopted.getValue());
        }
    }

    private boolean handOver(Object resource, long bytes) {
        for (Lease other : leases) {
            if (other.adopt(resource, bytes)) return true;
        }
        return false;
    }

    static Set<Object> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    static final class Lease {
        private final Set<Object> pinned;
        private final IdentityHashMap<Object, Long> adopted = new IdentityHashMap<>();
        private final long exclusiveBytes;

        private Lease(Set<Object> pinned, long exclusiveBytes) {
            this.pinned = pinned;
            this.exclusiveBytes = exclusiveBytes;
        }

        private boolean adopt(Object resource, long bytes) {
            if (!pinned.remove(resource)) return false;
            adopted.put(resource, bytes);
            return true;
        }
    }
}
