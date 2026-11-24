package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;

public final class LockedDatabase implements AutoCloseable {
    private static final Duration FILE_LOCK_WAIT = Duration.ZERO;
    private static final String LOCK_REMEDY = "The database could not be locked for this command. Stop the modl server that uses"
        + " it, then run the command again. (";
    private static final AdvisoryInstanceLock.Timing COMMAND_LOCK_TIMING = shortWait(true);
    private static final AdvisoryInstanceLock.Timing BESIDE_SERVER_LOCK_TIMING = shortWait(false);

    private final OfflineDatabase database;
    private final InstanceLock lock;

    private LockedDatabase(OfflineDatabase database, InstanceLock lock) {
        this.database = database;
        this.lock = lock;
    }

    public static LockedDatabase lock(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory) {
        return lock(database, dataDirectory, LockedDatabase.class.getClassLoader());
    }

    static LockedDatabase lock(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory, ClassLoader migrations) {
        OfflineDatabase offline = OfflineDatabase.open(database, dataDirectory, migrations);
        return locked(offline, new LockRelease(offline.access().tracker(), LockedDatabase.class.getClassLoader()),
            COMMAND_LOCK_TIMING);
    }

    static LockedDatabase lockBesideRunningServer(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory) {
        OfflineDatabase offline = OfflineDatabase.open(database, dataDirectory, LockedDatabase.class.getClassLoader());
        return locked(offline, LockRelease.keepingDrivers(offline.access().tracker()), BESIDE_SERVER_LOCK_TIMING);
    }

    private static LockedDatabase locked(OfflineDatabase offline, LockRelease release, AdvisoryInstanceLock.Timing timing) {
        try {
            return new LockedDatabase(offline, acquire(offline.access(), release, timing));
        } catch (RuntimeException e) {
            offline.close();
            throw e;
        }
    }

    public BackupSource source() {
        return database.source();
    }

    public RestoreTarget target() {
        return database.access().restoreTarget();
    }

    @Override
    public void close() {
        try {
            database.close();
        } finally {
            lock.destroy();
        }
    }

    DatabaseAccess access() {
        return database.access();
    }

    private static AdvisoryInstanceLock.Timing shortWait(boolean announcesWait) {
        AdvisoryInstanceLock.Timing standard = AdvisoryInstanceLock.Timing.DEFAULT;
        return new AdvisoryInstanceLock.Timing(Duration.ofSeconds(2), Duration.ofMillis(250), standard.unreachableBudget(),
            standard.heartbeatInterval(), announcesWait);
    }

    private static InstanceLock acquire(DatabaseAccess access, LockRelease release, AdvisoryInstanceLock.Timing timing) {
        SqlDatabaseProperties database = access.database();
        try {
            if (!database.dialect().isExternal()) {
                return FileInstanceLock.acquire(access.dataDirectory().database(), release, FILE_LOCK_WAIT);
            }
            requireReachable(access);
            return AdvisoryInstanceLock.acquire(database.dialect(),
                ExternalDatabaseConnections.lockConnections(database, access.tracker()), access.fence(), access.notice(),
                release, timing);
        } catch (InstanceLockHeldException e) {
            throw new DatabaseInUseException(LOCK_REMEDY + e.getMessage() + ")", e);
        } catch (IllegalStateException e) {
            throw new DatabaseToolException(LOCK_REMEDY + e.getMessage() + ")", e);
        }
    }

    private static void requireReachable(DatabaseAccess access) {
        SqlDatabaseProperties database = access.database();
        DataSource connections = access.tracked(ExternalSessions.migration(DriverDataSource.of(database), database.dialect()));
        try (Connection connection = connections.getConnection()) {
            access.statements().number(connection, "SELECT 1");
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("connect to the database", e);
        }
    }
}
