package gg.modl.minecraft.replay.recording;

public enum RecordingCause {
    AUTO_RECORD(0),
    ANTICHEAT_FLAG(1),
    STAFF(2);

    private final int priority;

    RecordingCause(int priority) {
        this.priority = priority;
    }

    boolean outranks(RecordingCause other) {
        return priority > other.priority;
    }
}
