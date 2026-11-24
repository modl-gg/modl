package gg.modl.backend.database.sql;

import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import gg.modl.backend.database.DatabaseDialect;
import org.jspecify.annotations.Nullable;
import java.util.Optional;
import javax.sql.DataSource;

final class ExternalDatabaseConnections implements DatabaseConnections {
    private final DriverDataSource driver;
    private final DatabaseDialect dialect;
    private final int poolSize;
    private final ConnectionTracker tracker;

    ExternalDatabaseConnections(SqlDatabaseProperties database, ConnectionTracker tracker) {
        this.dialect = database.dialect();
        this.driver = DriverDataSource.of(database);
        this.poolSize = database.poolSize();
        this.tracker = tracker;
    }

    static DataSource lockConnections(SqlDatabaseProperties database, ConnectionTracker tracker) {
        return tracker.track(ExternalSessions.lock(DriverDataSource.of(database), database.dialect()), ConnectionRole.LOCK);
    }

    @Override
    public DataSource migration() {
        return tracker.track(ExternalSessions.migration(driver, dialect), ConnectionRole.MIGRATION);
    }

    @Override
    public ConnectionPools openPools(@Nullable MetricsTrackerFactory metrics) {
        return ExternalDataSources.open(ExternalSessions.pooled(driver, dialect), dialect, poolSize, tracker, metrics);
    }

    @Override
    public Optional<SqliteConnections> sqlite() {
        return Optional.empty();
    }
}
