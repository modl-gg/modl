package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.DatabaseUnavailableException.Reason;
import java.util.concurrent.atomic.AtomicReference;

public final class InstanceFence {
    public enum State {
        SERVING,
        DRAINING,
        FENCED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.SERVING);

    public State state() {
        return state.get();
    }

    public boolean isServing() {
        return state.get() == State.SERVING;
    }

    public void requireServing() {
        switch (state.get()) {
            case SERVING -> {
            }
            case DRAINING -> throw new DatabaseUnavailableException(Reason.SHUTTING_DOWN);
            case FENCED -> throw new DatabaseUnavailableException(Reason.LOCK_LOST);
        }
    }

    public void beginDraining() {
        state.compareAndSet(State.SERVING, State.DRAINING);
    }

    public boolean fence() {
        return state.getAndSet(State.FENCED) != State.FENCED;
    }
}
