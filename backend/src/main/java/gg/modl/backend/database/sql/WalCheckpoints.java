package gg.modl.backend.database.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;

public final class WalCheckpoints {
    private final Optional<DataSource> checkpoints;
    private final InstanceFence fence;

    WalCheckpoints(Optional<SqliteConnections> connections, ConnectionTracker tracker, InstanceFence fence) {
        this.checkpoints = connections.map(sqlite -> tracker.track(sqlite.checkpoint(), ConnectionRole.MAINTENANCE));
        this.fence = fence;
    }

    public void checkpoint() {
        if (checkpoints.isEmpty() || !fence.isServing()) {
            return;
        }
        try (Connection connection = checkpoints.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(PASSIVE)");
        } catch (SQLException e) {
            throw new IllegalStateException("The database write-ahead log could not be checkpointed", e);
        }
    }
}
