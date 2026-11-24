package gg.modl.update;

import org.jspecify.annotations.Nullable;

public final class Selection {
    public enum Target {
        BASE,
        PENDING,
        ROLLBACK,
        COMMITTED,
        REFUSED
    }

    private final Target target;
    private final @Nullable ReleaseVersion version;
    private final @Nullable String artifact;
    private final @Nullable UpdateTransaction pendingToWrite;
    private final boolean deletePending;
    private final @Nullable String refusal;

    private Selection(Target target, @Nullable ReleaseVersion version, @Nullable String artifact, @Nullable UpdateTransaction pendingToWrite,
                      boolean deletePending, @Nullable String refusal) {
        this.target = target;
        this.version = version;
        this.artifact = artifact;
        this.pendingToWrite = pendingToWrite;
        this.deletePending = deletePending;
        this.refusal = refusal;
    }

    static Selection base(ReleaseVersion base, boolean deletePending) {
        return new Selection(Target.BASE, base, null, null, deletePending, null);
    }

    static Selection committed(ReleaseVersion version, String artifact, boolean deletePending) {
        return new Selection(Target.COMMITTED, version, artifact, null, deletePending, null);
    }

    static Selection pendingTarget(ReleaseVersion version, String artifact, UpdateTransaction attempt) {
        return new Selection(Target.PENDING, version, artifact, attempt, false, null);
    }

    static Selection rollbackTarget(ReleaseVersion version, String artifact, @Nullable UpdateTransaction write) {
        return new Selection(Target.ROLLBACK, version, artifact, write, false, null);
    }

    static Selection rollbackToBase(ReleaseVersion base, @Nullable UpdateTransaction write) {
        return new Selection(Target.ROLLBACK, base, null, write, false, null);
    }

    static Selection refused(String refusal) {
        return new Selection(Target.REFUSED, null, null, null, false, refusal);
    }

    public Target target() {
        return target;
    }

    public ReleaseVersion version() {
        if (version == null) {
            throw new IllegalStateException("A refused selection names no version");
        }
        return version;
    }

    public @Nullable String artifact() {
        return artifact;
    }

    public boolean usesBase() {
        return artifact == null && target != Target.REFUSED;
    }

    public boolean supervisedWithDeadline() {
        return target == Target.PENDING;
    }

    public @Nullable UpdateTransaction pendingToWrite() {
        return pendingToWrite;
    }

    public boolean deletePending() {
        return deletePending;
    }

    public String refusal() {
        if (refusal == null) {
            throw new IllegalStateException("Only a refused selection has a refusal");
        }
        return refusal;
    }
}
