package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.update.ReleaseBuild;
import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

final class OfflineUpgrade {
    private OfflineUpgrade() {
    }

    static void run(DatabaseAccess access, DatabaseDialect dialect, DataSource connections, Instant now) {
        Flyway flyway = access.flyway(dialect, connections).load();
        boolean advances = flyway.info().pending().length > 0;
        DatabaseMigration.migrate(dialect, flyway);
        if (advances) {
            PreMigrationState.recordRunningVersion(connections, dialect, ReleaseBuild.current().versionText(), now);
        }
    }
}
