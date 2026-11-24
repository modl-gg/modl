package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration(proxyBeanMethods = false)
public class MaintenanceConfiguration {
    @Bean
    @DependsOn({"instanceLock", "databaseConnections"})
    DatabaseAccess databaseAccess(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory, ConnectionTracker tracker,
                                  RunningStatements running, StatementDeadline statementDeadline, InstanceFence fence,
                                  OperatorNotice notice) {
        return new DatabaseAccess(database, dataDirectory, tracker, running, statementDeadline, fence, notice,
            MaintenanceConfiguration.class.getClassLoader());
    }

    @Bean
    BackupSource backupSource(DatabaseAccess access) {
        return access.backupSource();
    }

    @Bean
    RestoreTarget restoreTarget(DatabaseAccess access) {
        return access.restoreTarget();
    }

    @Bean
    SqliteMaintenance sqliteMaintenance(DatabaseConnections connections, DatabaseAccess access, DSLContext dsl, UnitOfWork unitOfWork,
                                        WalCheckpoints walCheckpoints, ScheduledDatabaseJobs jobs) {
        return new SqliteMaintenance(connections.sqlite(), access, dsl, unitOfWork, walCheckpoints, jobs);
    }
}
