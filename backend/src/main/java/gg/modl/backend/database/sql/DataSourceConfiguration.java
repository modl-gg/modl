package gg.modl.backend.database.sql;

import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.sql.Connection;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class DataSourceConfiguration {
    @Bean
    DatabaseDialect databaseDialect(SqlDatabaseProperties database) {
        return database.dialect();
    }

    @Bean
    ConnectionTracker connectionTracker() {
        return new ConnectionTracker();
    }

    @Bean
    InstanceFence instanceFence() {
        return new InstanceFence();
    }

    @Bean
    RunningStatements runningStatements() {
        return new RunningStatements();
    }

    @Bean(destroyMethod = "destroy")
    InstanceLock instanceLock(DatabaseDialect dialect, DataDirectoryProperties dataDirectory, ConnectionTracker tracker,
                              InstanceFence fence, OperatorNotice notice, SqlDatabaseProperties database) {
        LockRelease release = new LockRelease(tracker, DataSourceConfiguration.class.getClassLoader());
        if (dialect.isExternal()) {
            return AdvisoryInstanceLock.acquire(dialect, ExternalDatabaseConnections.lockConnections(database, tracker),
                fence, notice, release, AdvisoryInstanceLock.Timing.DEFAULT);
        }
        return FileInstanceLock.acquire(dataDirectory.database(), release, FileInstanceLock.WAIT_BUDGET);
    }

    @Bean
    DatabaseConnections databaseConnections(InstanceLock instanceLock, SqlDatabaseProperties database,
                                            DataDirectoryProperties dataDirectory, ConnectionTracker tracker,
                                            OperatorNotice notice) {
        if (database.dialect().isExternal()) {
            return new ExternalDatabaseConnections(database, tracker);
        }
        return SqliteDatabaseConnections.prepare(dataDirectory.database(), SqliteLocations.nativeParent(database, dataDirectory),
            SqliteLocations.temporaryDirectory(dataDirectory), tracker, notice);
    }

    @Bean(destroyMethod = "closeIfIdle")
    ConnectionPools connectionPools(DatabaseConnections connections, DatabaseMigration migration,
                                    ObjectProvider<MeterRegistry> meterRegistry) {
        MeterRegistry registry = meterRegistry.getIfUnique();
        return connections.openPools(registry == null ? null : new MicrometerMetricsTrackerFactory(registry));
    }

    @Bean
    @Primary
    DataSource dataSource(ConnectionPools pools, InstanceFence fence, DatabaseDialect dialect) {
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(new RoutingDataSource(pools, fence));
        lazy.setDefaultAutoCommit(true);
        lazy.setDefaultTransactionIsolation(dialect.isExternal()
            ? Connection.TRANSACTION_READ_COMMITTED
            : Connection.TRANSACTION_SERIALIZABLE);
        return lazy;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    WalCheckpoints walCheckpoints(DatabaseConnections connections, ConnectionTracker tracker, InstanceFence fence) {
        return new WalCheckpoints(connections.sqlite(), tracker, fence);
    }

    @Bean
    SnapshotConnections snapshotConnections(DatabaseConnections connections, ConnectionTracker tracker,
                                            RunningStatements running, InstanceFence fence) {
        return new SnapshotConnections(connections.sqlite(), tracker, running, fence);
    }
}
