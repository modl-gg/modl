package gg.modl.backend.database.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import gg.modl.backend.database.DatabaseDialect;
import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.util.Locale;
import javax.sql.DataSource;

final class ExternalDataSources implements ConnectionPools {
    static final Duration MAX_LIFETIME = Duration.ofMinutes(30);

    private static final String READ_COMMITTED = "TRANSACTION_READ_COMMITTED";

    private final HikariDataSource pool;

    private ExternalDataSources(HikariDataSource pool) {
        this.pool = pool;
    }

    static ExternalDataSources open(DataSource connections, DatabaseDialect dialect, int poolSize, ConnectionTracker tracker,
                                    @Nullable MetricsTrackerFactory metrics) {
        HikariConfig config = HikariPools.config("modl-" + dialect.name().toLowerCase(Locale.ROOT),
            tracker.track(connections, ConnectionRole.POOL), poolSize, MAX_LIFETIME, metrics);
        config.setTransactionIsolation(READ_COMMITTED);
        return new ExternalDataSources(new HikariDataSource(config));
    }

    @Override
    public DataSource writer() {
        return pool;
    }

    @Override
    public DataSource reader() {
        return pool;
    }

    @Override
    public boolean closeIfIdle() {
        return HikariPools.closeIfIdle(pool);
    }
}
