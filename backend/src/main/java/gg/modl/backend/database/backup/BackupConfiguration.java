package gg.modl.backend.database.backup;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.sql.BackupSource;
import gg.modl.backend.database.sql.RestoreTarget;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BackupConfiguration {
    @Bean
    DatabaseBackup databaseBackup(BackupSource source, DataDirectoryProperties dataDirectory, Clock clock) {
        return new ArchivingDatabaseBackup(source, dataDirectory.temporary(), BackupDiskSpace.SYSTEM, clock);
    }

    @Bean
    DatabaseCopy databaseCopy(DatabaseBackup backup, DataDirectoryProperties dataDirectory, Clock clock) {
        return new DatabaseCopy(backup, dataDirectory.temporary(), BackupDiskSpace.SYSTEM, clock);
    }

    @Bean
    DatabaseRestore databaseRestore(RestoreTarget target) {
        return new DatabaseRestore(target, BackupDiskSpace.SYSTEM);
    }
}
