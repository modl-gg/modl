package gg.modl.backend.database.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SnapshotConnections {
    private final Optional<DataSource> snapshots;
    private final RunningStatements running;
    private final InstanceFence fence;

    SnapshotConnections(Optional<SqliteConnections> connections, ConnectionTracker tracker, RunningStatements running,
                               InstanceFence fence) {
        this.snapshots = connections.map(sqlite -> tracker.track(sqlite.snapshot(), ConnectionRole.SNAPSHOT));
        this.running = running;
        this.fence = fence;
    }

    public void vacuumInto(Path target) throws SQLException {
        fence.requireServing();
        DataSource source = snapshots.orElseThrow(() -> new IllegalStateException("Snapshots need the built-in SQLite database"));
        boolean targetHeldData = holdsData(target);
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toString());
            running.register(statement);
            try {
                statement.execute();
            } finally {
                running.deregister(statement);
            }
        } catch (SQLException | RuntimeException e) {
            if (!targetHeldData) {
                deletePartialCopy(target, e);
            }
            throw e;
        }
    }

    private static boolean holdsData(Path target) {
        try {
            return Files.exists(target) && Files.size(target) > 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static void deletePartialCopy(Path target, Exception failure) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            failure.addSuppressed(e);
            log.warn("Could not delete the partial database copy {}", target, e);
        }
    }
}
