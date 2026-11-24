package gg.modl.backend.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class UnitFrame {
    private static final ThreadLocal<UnitFrame> CURRENT = new ThreadLocal<>();

    private final UnitScope scope;
    private final long deadlineNanos;
    private final List<Runnable> afterCommit = new ArrayList<>();

    private UnitFrame(UnitScope scope, long deadlineNanos) {
        this.scope = scope;
        this.deadlineNanos = deadlineNanos;
    }

    public static Optional<UnitFrame> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static boolean routesToWriter() {
        UnitFrame frame = CURRENT.get();
        return frame != null && frame.scope == UnitScope.WRITE;
    }

    public static void requireWrite() {
        if (!routesToWriter()) {
            throw new IllegalStateException("This statement must run inside a write unit");
        }
    }

    public UnitScope scope() {
        return scope;
    }

    public boolean hasDeadline() {
        return scope == UnitScope.REPORT;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    static UnitFrame enter(UnitScope scope, long deadlineNanos) {
        UnitFrame frame = new UnitFrame(scope, deadlineNanos);
        CURRENT.set(frame);
        return frame;
    }

    static void exit() {
        CURRENT.remove();
    }

    void registerAfterCommit(Runnable effect) {
        afterCommit.add(effect);
    }

    List<Runnable> afterCommitEffects() {
        return List.copyOf(afterCommit);
    }
}
