package gg.modl.backend.database.sql;

import org.jspecify.annotations.Nullable;

final class LockRelease {
    private final ConnectionTracker tracker;
    private final @Nullable ClassLoader serverClassLoader;

    LockRelease(ConnectionTracker tracker, ClassLoader serverClassLoader) {
        this.tracker = tracker;
        this.serverClassLoader = serverClassLoader;
    }

    private LockRelease(ConnectionTracker tracker) {
        this.tracker = tracker;
        this.serverClassLoader = null;
    }

    static LockRelease keepingDrivers(ConnectionTracker tracker) {
        return new LockRelease(tracker);
    }

    boolean connectionsClosed() {
        return tracker.openConnections() == 0;
    }

    int openConnections() {
        return tracker.openConnections();
    }

    void releaseDrivers() {
        if (serverClassLoader != null) {
            JdbcDrivers.releaseLoadedBy(serverClassLoader);
        }
    }
}
