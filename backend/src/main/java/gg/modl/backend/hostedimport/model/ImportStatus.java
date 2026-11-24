package gg.modl.backend.hostedimport.model;

import java.util.EnumSet;
import java.util.Set;

public enum ImportStatus {
    NONE,
    RECEIVING,
    DOWNLOADING,
    VERIFYING,
    REVIEW,
    APPLYING,
    PAUSED,
    COMPLETED,
    ABANDONED,
    FAILED;

    private static final Set<ImportStatus> IN_PROGRESS = EnumSet.of(RECEIVING, DOWNLOADING, VERIFYING, REVIEW, APPLYING, PAUSED);
    private static final Set<ImportStatus> DISCARDABLE = EnumSet.of(RECEIVING, DOWNLOADING, VERIFYING, REVIEW, FAILED);
    private static final Set<ImportStatus> FINAL = EnumSet.of(COMPLETED, ABANDONED);

    public boolean isInProgress() {
        return IN_PROGRESS.contains(this);
    }

    public boolean isDiscardable() {
        return DISCARDABLE.contains(this);
    }

    public boolean isFinal() {
        return FINAL.contains(this);
    }

    public boolean hasWrittenData() {
        return this == APPLYING || this == PAUSED || isFinal();
    }
}
