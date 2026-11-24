package gg.modl.backend.database.sql;

import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import org.jspecify.annotations.Nullable;
import java.util.Optional;
import javax.sql.DataSource;

interface DatabaseConnections {
    DataSource migration();

    ConnectionPools openPools(@Nullable MetricsTrackerFactory metrics);

    Optional<SqliteConnections> sqlite();
}
