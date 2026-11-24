package gg.modl.update;

import org.jspecify.annotations.Nullable;

public final class ArtifactSelection {
    private ArtifactSelection() {
    }

    public static Selection select(ReleaseVersion base, TransactionKind kind, boolean sameLineOnly, UpdateState state) {
        boolean deletePending = state.pendingAlreadyCommitted();
        UpdateTransaction pending = state.pending();
        if (pending != null && pending.managed() && pending.kind() == kind && appliesTo(base, sameLineOnly, pending)) {
            return fromPending(base, pending);
        }
        return committedOrBase(base, kind, sameLineOnly, state.committed(), deletePending);
    }

    public static Selection forCommand(ReleaseVersion base, TransactionKind kind, boolean sameLineOnly, UpdateState state) {
        return committedOrBase(base, kind, sameLineOnly, state.committed(), false);
    }

    private static Selection committedOrBase(ReleaseVersion base, TransactionKind kind, boolean sameLineOnly, @Nullable CommittedUpdate committed,
                                             boolean deletePending) {
        if (committed != null && committed.kind() == kind && committed.artifact() != null && committed.version().isNewerThan(base)
            && (!sameLineOnly || committed.version().sameLine(base))) {
            return Selection.committed(committed.version(), committed.artifact(), deletePending);
        }
        return Selection.base(base, deletePending);
    }

    private static boolean appliesTo(ReleaseVersion base, boolean sameLineOnly, UpdateTransaction pending) {
        if (!base.isOlderThan(pending.to())) {
            return false;
        }
        return !sameLineOnly || pending.to().sameLine(base);
    }

    private static Selection fromPending(ReleaseVersion base, UpdateTransaction pending) {
        if (pending.phase() == UpdatePhase.ROLLBACK) {
            return rollback(base, pending, null);
        }
        if (pending.exhaustedAttempts() && pending.rollbackAllowed()) {
            UpdateTransaction rolledBack = pending.rollingBack();
            return rollback(base, rolledBack, rolledBack);
        }
        if (pending.toArtifact() == null) {
            return Selection.refused("The update to modl " + pending.to() + " names no artifact. Replace the modl jar with modl "
                + pending.to() + " or move updates/pending.json away.");
        }
        UpdateTransaction attempt = pending.toBuilder().attempts(pending.attempts() + 1).build();
        return Selection.pendingTarget(pending.to(), pending.toArtifact(), attempt);
    }

    private static Selection rollback(ReleaseVersion base, UpdateTransaction pending, @Nullable UpdateTransaction write) {
        if (pending.fromArtifact() != null) {
            return Selection.rollbackTarget(pending.from(), pending.fromArtifact(), write);
        }
        if (base.equals(pending.from())) {
            return Selection.rollbackToBase(base, write);
        }
        return Selection.refused("The update from modl " + pending.from() + " to " + pending.to() + " must be rolled back by modl "
            + pending.from() + ", but this is modl " + base + ". Start modl " + pending.from() + " to restore the database.");
    }
}
