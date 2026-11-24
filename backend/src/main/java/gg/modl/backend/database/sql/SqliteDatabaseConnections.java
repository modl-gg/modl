package gg.modl.backend.database.sql;

import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.sql.DataSource;

final class SqliteDatabaseConnections implements DatabaseConnections {
    private final SqliteConnections connections;
    private final ConnectionTracker tracker;

    private SqliteDatabaseConnections(SqliteConnections connections, ConnectionTracker tracker) {
        this.connections = connections;
        this.tracker = tracker;
    }

    static SqliteDatabaseConnections prepare(Path databaseDirectory, Path nativeParent, Path temporaryDirectory,
                                                    ConnectionTracker tracker, OperatorNotice notice) {
        createDirectories(temporaryDirectory);
        SqliteNativeLibrary.initialize(nativeParent);
        SqliteConnections connections = new SqliteConnections(databaseDirectory.resolve(SqliteConnections.DATABASE_FILE_NAME),
            temporaryDirectory);
        SqliteDatabaseFile.bootstrap(connections, tracker, notice);
        return new SqliteDatabaseConnections(connections, tracker);
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the SQLite temporary directory " + directory, e);
        }
    }

    @Override
    public DataSource migration() {
        return tracker.track(connections.migration(), ConnectionRole.MIGRATION);
    }

    @Override
    public ConnectionPools openPools(@Nullable MetricsTrackerFactory metrics) {
        return SqliteDataSources.open(connections, tracker, metrics);
    }

    @Override
    public Optional<SqliteConnections> sqlite() {
        return Optional.of(connections);
    }
}
