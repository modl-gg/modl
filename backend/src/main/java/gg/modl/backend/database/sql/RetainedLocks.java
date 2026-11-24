package gg.modl.backend.database.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class RetainedLocks {
    private static final List<Object> RETAINED = new ArrayList<>();

    private RetainedLocks() {
    }

    static synchronized void retain(Object lockHolder) {
        if (RETAINED.isEmpty()) {
            keepReachableUntilExit();
        }
        RETAINED.add(lockHolder);
    }

    private static void keepReachableUntilExit() {
        Thread.ofPlatform().daemon().name("modl-retained-locks").start(RetainedLocks::awaitExit);
    }

    private static void awaitExit() {
        new CompletableFuture<Void>().join();
    }

    static synchronized int size() {
        return RETAINED.size();
    }
}
