package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.infrastructure.logging.OperatorNotice;

public final class OfflineDatabase implements AutoCloseable {
    private final DatabaseAccess access;

    private OfflineDatabase(DatabaseAccess access) {
        this.access = access;
    }

    public static OfflineDatabase open(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory) {
        return open(database, dataDirectory, OfflineDatabase.class.getClassLoader());
    }

    static OfflineDatabase open(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory, ClassLoader migrations) {
        RunningStatements running = new RunningStatements();
        OfflineDatabase offline = new OfflineDatabase(new DatabaseAccess(database, dataDirectory, new ConnectionTracker(), running,
            new StatementDeadline(running), new InstanceFence(), new OperatorNotice(), migrations));
        try {
            if (!database.dialect().isExternal()) {
                offline.access.prepareSqlite();
            }
            return offline;
        } catch (RuntimeException e) {
            offline.close();
            throw e;
        }
    }

    public BackupSource source() {
        return access.backupSource();
    }

    DatabaseAccess access() {
        return access;
    }

    @Override
    public void close() {
        access.statementDeadline().close();
        access.running().close();
    }
}
