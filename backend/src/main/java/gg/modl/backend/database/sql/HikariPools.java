package gg.modl.backend.database.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import org.jspecify.annotations.Nullable;
import java.time.Duration;
import javax.sql.DataSource;

final class HikariPools {
    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    static final Duration NO_MAX_LIFETIME = Duration.ZERO;

    private HikariPools() {
    }

    static HikariConfig config(String poolName, DataSource connections, int size, Duration maxLifetime,
                               @Nullable MetricsTrackerFactory metrics) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setDataSource(connections);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(Math.min(1, size));
        config.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        config.setMaxLifetime(maxLifetime.toMillis());
        config.setAutoCommit(true);
        config.setMetricsTrackerFactory(metrics);
        return config;
    }

    static boolean closeIfIdle(HikariDataSource pool) {
        if (pool.isClosed()) {
            return true;
        }
        HikariPoolMXBean bean = pool.getHikariPoolMXBean();
        if (bean != null && bean.getActiveConnections() > 0) {
            return false;
        }
        pool.close();
        return true;
    }
}
