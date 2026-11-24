package gg.modl.backend.database.sql;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

public final class SqliteSnapshots {
    private final DatabaseAccess access;
    private final SnapshotConnections connections;

    SqliteSnapshots(DatabaseAccess access, SqliteConnections live) {
        this.access = access;
        this.connections = new SnapshotConnections(Optional.of(live), access.tracker(), access.running(), access.fence());
    }

    public SqliteFile copyInto(Path target) {
        try {
            connections.vacuumInto(target);
        } catch (SQLException e) {
            access.fence().requireServing();
            throw DatabaseToolException.failedTo("copy the database", e);
        }
        SqliteFile copy = access.sqliteFile(target);
        copy.requireIntact();
        return copy;
    }
}
