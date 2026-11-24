package gg.modl.backend.migration.config;

import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.migration.service.ImportFileIntake;
import gg.modl.backend.migration.service.ImportUploadFiles;
import gg.modl.backend.migration.service.ImportUploadReceiver;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ImportUploadConfiguration {

    @Bean
    ImportUploadFiles importUploadFiles(MigrationConfiguration migrationConfiguration) {
        return new ImportUploadFiles(Path.of(migrationConfiguration.getUploadDir()));
    }

    @Bean
    ImportUploadReceiver importUploadReceiver(ImportFileIntake intake, ImportUploadFiles files,
                                              MigrationConfiguration migrationConfiguration, Clock clock) {
        return new ImportUploadReceiver(intake, files, DiskSpaceFloor.DEFAULT, migrationConfiguration.maxUploadBytes(), clock);
    }
}
