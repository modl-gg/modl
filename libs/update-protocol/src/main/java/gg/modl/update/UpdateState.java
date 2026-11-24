package gg.modl.update;

import org.jspecify.annotations.Nullable;

public final class UpdateState {
    private final @Nullable UpdateTransaction pending;
    private final @Nullable CommittedUpdate committed;
    private final boolean pendingAlreadyCommitted;

    UpdateState(@Nullable UpdateTransaction pending, @Nullable CommittedUpdate committed, boolean pendingAlreadyCommitted) {
        this.pending = pending;
        this.committed = committed;
        this.pendingAlreadyCommitted = pendingAlreadyCommitted;
    }

    public @Nullable UpdateTransaction pending() {
        return pending;
    }

    public @Nullable CommittedUpdate committed() {
        return committed;
    }

    public boolean pendingAlreadyCommitted() {
        return pendingAlreadyCommitted;
    }
}
