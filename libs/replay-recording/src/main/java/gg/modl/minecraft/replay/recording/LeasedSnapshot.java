package gg.modl.minecraft.replay.recording;

final class LeasedSnapshot {
    private final RecordingSnapshot snapshot;
    private final RecordingMemory memory;
    private final RecordingMemory.Lease lease;

    LeasedSnapshot(RecordingSnapshot snapshot, RecordingMemory memory, RecordingMemory.Lease lease) {
        this.snapshot = snapshot;
        this.memory = memory;
        this.lease = lease;
    }

    RecordingSnapshot snapshot() {
        return snapshot;
    }

    void release() {
        memory.release(lease);
    }
}
