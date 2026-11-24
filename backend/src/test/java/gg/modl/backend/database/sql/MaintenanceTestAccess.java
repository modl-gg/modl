package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.support.sql.SqlTestDatabase;

final class MaintenanceTestAccess {
    private MaintenanceTestAccess() {
    }

    static DatabaseAccess of(SqlTestDatabase database) {
        return new DatabaseAccess(database.bean(SqlDatabaseProperties.class), database.bean(DataDirectoryProperties.class),
            database.connectionTracker(), database.bean(RunningStatements.class), database.bean(StatementDeadline.class),
            database.fence(), database.bean(OperatorNotice.class), MaintenanceTestAccess.class.getClassLoader());
    }
}
