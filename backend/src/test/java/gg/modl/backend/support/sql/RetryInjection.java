package gg.modl.backend.support.sql;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.WriteAttemptHook;
import java.util.concurrent.atomic.AtomicInteger;

public final class RetryInjection implements WriteAttemptHook {
    private final AtomicInteger injected = new AtomicInteger();

    @Override
    public void beforeCommit(int attempt) {
        if (attempt == 1) {
            injected.incrementAndGet();
            throw new ConcurrentUpdateException("Injected conflict to prove the unit is re-executable");
        }
    }

    public int injected() {
        return injected.get();
    }
}
